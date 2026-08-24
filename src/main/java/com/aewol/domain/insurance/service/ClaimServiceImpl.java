package com.aewol.domain.insurance.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.storage.FileStorage;
import com.aewol.domain.insurance.dto.ClaimConfirmRequest;
import com.aewol.domain.insurance.dto.ClaimResponse;
import com.aewol.domain.insurance.mapper.InsuranceMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.external.paddleocr.PaddleOcrClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimServiceImpl implements ClaimService {

    private static final long MAX_RECEIPT_SIZE_BYTES = 10L * 1024 * 1024;
    /** 업로드 경로로 공개되므로 스크립트가 실행될 수 있는 형식(svg, html 등)은 받지 않는다. */
    private static final Map<String, String> ALLOWED_IMAGE_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/jpg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private final InsuranceMapper insuranceMapper;
    private final PetMapper petMapper;
    private final PaddleOcrClient paddleOcrClient;
    private final FileStorage fileStorage;

    @Override
    public ClaimResponse createClaim(String memberId, String petId, MultipartFile receipt) {
        if (petMapper.findByIdAndMemberId(petId, memberId) == null) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
        String receiptKey = null;
        // insert가 지나가면 청구 행은 이미 커밋돼 있다. 이 메서드는 OCR을 트랜잭션 밖에서
        // 부르려고 @Transactional을 붙이지 않았으므로, 뒤에서 무엇이 실패하든 행은 남는다.
        // 그 뒤에 영수증을 지우면 멀쩡히 만들어진 청구가 깨진 이미지를 가리키게 된다.
        boolean claimPersisted = false;
        try {
            // 저장 위치는 FileStorage가 정한다. 예전에는 여기서 디스크에 직접 쓰고
            // "/uploads/receipts/..." 형태의 URL을 DB에 넣었는데, 조회는 이미
            // fileStorage.signedUrl로 하고 있어 저장소를 옮기면 읽기와 어긋났다.
            byte[] receiptBytes = readValidatedReceipt(receipt);
            receiptKey = fileStorage.store(receiptBytes, "receipts",
                    detectImageExtension(receiptBytes));

            // PaddleOCR (트랜잭션 밖에서 호출 - 응답 지연이 DB 커넥션을 점유하지 않도록)
            String extractedJson = paddleOcrClient.extractReceiptData(receiptBytes, receipt.getContentType());

            Map<String, Object> claim = new HashMap<>();
            claim.put("petId", petId);
            claim.put("memberId", memberId);
            claim.put("receiptImageUrl", receiptKey);
            claim.put("extractedData", extractedJson);
            claim.put("hospitalName", null);
            claim.put("treatmentDate", null);
            claim.put("totalAmount", null);
            claim.put("claimStatus", "DRAFT");
            insuranceMapper.insertClaim(claim);
            claimPersisted = true;

            return toResponse(insuranceMapper.findClaimById(String.valueOf(claim.get("claimId"))));
        } catch (IOException e) {
            if (!claimPersisted) {
                deleteReceiptQuietly(receiptKey);
            }
            throw new BusinessException("영수증 업로드에 실패했습니다.", e);
        } catch (RuntimeException e) {
            if (!claimPersisted) {
                deleteReceiptQuietly(receiptKey);
            }
            throw e;
        }
    }

    @Override
    @Transactional
    public ClaimResponse confirmClaim(String memberId, String claimId, ClaimConfirmRequest correctedData) {
        Map<String, Object> existing = insuranceMapper.findClaimById(claimId);
        if (existing == null || !String.valueOf(existing.get("member_id")).equals(memberId)) {
            throw BusinessException.notFound("청구 정보를 찾을 수 없습니다.");
        }
        if (!"DRAFT".equals(existing.get("claim_status"))) {
            throw BusinessException.conflict("이미 제출된 청구입니다.");
        }

        Map<String, Object> update = new HashMap<>();
        update.put("claimId", claimId);
        Object hospitalName = correctedData == null
                ? existing.get("hospital_name") : correctedData.getHospitalName().trim();
        Object treatmentDate = correctedData == null
                ? existing.get("treatment_date") : correctedData.getTreatmentDate();
        Object totalAmount = correctedData == null
                ? existing.get("total_amount") : correctedData.getTotalAmount();
        validateFinalClaim(hospitalName, treatmentDate, totalAmount);

        update.put("hospitalName", hospitalName);
        update.put("treatmentDate", treatmentDate);
        update.put("totalAmount", totalAmount);
        update.put("extractedData", existing.get("extracted_data"));
        update.put("claimStatus", "SUBMITTED");
        update.put("claimDocumentUrl", null);
        insuranceMapper.updateClaim(update);

        return toResponse(insuranceMapper.findClaimById(claimId));
    }

    private void validateFinalClaim(Object hospitalName, Object treatmentDate, Object totalAmount) {
        if (!(hospitalName instanceof String) || ((String) hospitalName).trim().isEmpty()) {
            throw new BusinessException("병원명을 확인해주세요.");
        }
        if (((String) hospitalName).trim().length() > 100) {
            throw new BusinessException("병원명은 100자 이하여야 합니다.");
        }

        LocalDate parsedTreatmentDate;
        try {
            parsedTreatmentDate = treatmentDate instanceof LocalDate
                    ? (LocalDate) treatmentDate : LocalDate.parse(String.valueOf(treatmentDate));
        } catch (DateTimeParseException e) {
            throw new BusinessException("진료일 형식이 올바르지 않습니다.");
        }
        if (parsedTreatmentDate.isAfter(LocalDate.now())) {
            throw new BusinessException("진료일은 오늘 이후일 수 없습니다.");
        }

        if (!(totalAmount instanceof BigDecimal) || ((BigDecimal) totalAmount).signum() <= 0) {
            throw new BusinessException("청구 금액은 0보다 커야 합니다.");
        }
    }

    @Override
    public List<ClaimResponse> getClaims(String memberId) {
        return insuranceMapper.findClaimsByMemberId(memberId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public ClaimResponse getClaim(String memberId, String claimId) {
        Map<String, Object> existing = insuranceMapper.findClaimById(claimId);
        if (existing == null || !String.valueOf(existing.get("member_id")).equals(memberId)) {
            throw BusinessException.notFound("청구 정보를 찾을 수 없습니다.");
        }
        return toResponse(existing);
    }

    private ClaimResponse toResponse(Map<String, Object> claim) {
        return ClaimResponse.builder()
                .claimId(String.valueOf(claim.get("claim_id")))
                .petId(String.valueOf(claim.get("pet_id")))
                .hospitalName((String) claim.get("hospital_name"))
                .treatmentDate(claim.get("treatment_date") != null ? claim.get("treatment_date").toString() : null)
                .totalAmount(claim.get("total_amount") != null ? (BigDecimal) claim.get("total_amount") : null)
                .claimStatus((String) claim.get("claim_status"))
                .claimDocumentUrl((String) claim.get("claim_document_url"))
                .receiptImageUrl(fileStorage.signedUrl((String) claim.get("receipt_image_url")))
                .extractedData(claim.get("extracted_data"))
                .build();
    }

    /**
     * 청구가 저장되기 전에 실패했을 때 방금 올린 영수증만 지운다.
     *
     * <p>저장된 뒤에는 부르면 안 된다. 남아 있는 청구 행이 그 파일을 가리키기 때문이다.
     *
     * <p>삭제 실패는 {@link com.aewol.common.storage.FileStorage#delete(String)}가 삼킨다.
     * 여기서 새 예외가 나면 원래 실패 원인을 가려버린다.
     */
    private void deleteReceiptQuietly(String receiptKey) {
        if (receiptKey == null || receiptKey.isBlank()) {
            return;
        }
        fileStorage.delete(receiptKey);
    }

    /**
     * 영수증이 실제 이미지인지 확인한 뒤 바이트를 돌려준다.
     *
     * <p>원본 확장자를 그대로 쓰면 {@code .svg}나 {@code .html}이 업로드 경로로 공개돼
     * 스크립트가 실행될 수 있다. 그래서 (1) 빈 파일과 크기를 막고, (2) MIME 타입을
     * 허용 목록으로 제한하고, (3) 파일 앞머리 시그니처로 실제 이미지인지 확인한다.
     *
     * <p>저장 확장자는 파일명이 아니라 {@link #detectImageExtension(byte[])}가 정한다.
     */
    private byte[] readValidatedReceipt(MultipartFile receipt) throws IOException {
        if (receipt == null || receipt.isEmpty()) {
            throw new BusinessException("영수증 파일이 비어 있습니다.");
        }
        if (receipt.getSize() > MAX_RECEIPT_SIZE_BYTES) {
            throw new BusinessException("영수증은 10MB 이하만 업로드할 수 있습니다.");
        }
        String contentType = receipt.getContentType();
        String declaredExtension = contentType == null
                ? null
                : ALLOWED_IMAGE_TYPES.get(contentType.toLowerCase(Locale.ROOT));
        if (declaredExtension == null) {
            throw new BusinessException("JPG, PNG, WEBP 이미지만 올릴 수 있습니다.");
        }

        byte[] bytes = receipt.getBytes();
        if (detectImageExtension(bytes) == null) {
            throw new BusinessException("이미지 파일이 아니거나 손상된 파일입니다.");
        }
        return bytes;
    }

    /** 파일 앞머리 시그니처로 이미지 형식을 판별한다. 아니면 null. */
    private static String detectImageExtension(byte[] bytes) {
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && (bytes[4] & 0xFF) == 0x0D && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A && (bytes[7] & 0xFF) == 0x0A) {
            return "png";
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "webp";
        }
        return null;
    }
}
