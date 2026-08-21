package com.aewol.domain.insurance.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.storage.FileStorage;
import com.aewol.domain.insurance.dto.ClaimResponse;
import com.aewol.domain.insurance.mapper.InsuranceMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.external.paddleocr.PaddleOcrClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimServiceImpl implements ClaimService {

    private final InsuranceMapper insuranceMapper;
    private final PetMapper petMapper;
    private final PaddleOcrClient paddleOcrClient;
    private final FileStorage fileStorage;

    @Override
    public ClaimResponse createClaim(String memberId, String petId, MultipartFile receipt) {
        if (petMapper.findByIdAndMemberId(petId, memberId) == null) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
        try {
            // 저장 위치는 FileStorage가 정한다. 예전에는 여기서 디스크에 직접 쓰고
            // "/uploads/receipts/..." 형태의 URL을 DB에 넣었는데, 조회는 이미
            // fileStorage.signedUrl로 하고 있어 저장소를 옮기면 읽기와 어긋났다.
            String imageUrl = fileStorage.store(receipt.getBytes(), "receipts",
                    extensionOf(receipt.getOriginalFilename()));

            // PaddleOCR (트랜잭션 밖에서 호출 - 응답 지연이 DB 커넥션을 점유하지 않도록)
            String extractedJson = paddleOcrClient.extractReceiptData(
                    receipt.getBytes(), receipt.getContentType());

            Map<String, Object> claim = new HashMap<>();
            claim.put("petId", petId);
            claim.put("memberId", memberId);
            claim.put("receiptImageUrl", imageUrl);
            claim.put("extractedData", extractedJson);
            claim.put("hospitalName", null);
            claim.put("treatmentDate", null);
            claim.put("totalAmount", null);
            claim.put("claimStatus", "DRAFT");
            insuranceMapper.insertClaim(claim);

            return toResponse(insuranceMapper.findClaimById(String.valueOf(claim.get("claimId"))));
        } catch (IOException e) {
            throw new BusinessException("영수증 업로드에 실패했습니다.");
        }
    }

    @Override
    @Transactional
    public ClaimResponse confirmClaim(String memberId, String claimId, ClaimResponse correctedData) {
        Map<String, Object> existing = insuranceMapper.findClaimById(claimId);
        if (existing == null || !String.valueOf(existing.get("member_id")).equals(memberId)) {
            throw BusinessException.notFound("청구 정보를 찾을 수 없습니다.");
        }

        Map<String, Object> update = new HashMap<>();
        update.put("claimId", claimId);
        if (correctedData == null) {
            update.put("hospitalName", existing.get("hospital_name"));
            update.put("treatmentDate", existing.get("treatment_date"));
            update.put("totalAmount", existing.get("total_amount"));
            update.put("extractedData", existing.get("extracted_data"));
        } else {
            update.put("hospitalName", correctedData.getHospitalName());
            update.put("treatmentDate", correctedData.getTreatmentDate());
            update.put("totalAmount", correctedData.getTotalAmount());
            update.put("extractedData", existing.get("extracted_data"));
        }
        update.put("claimStatus", "SUBMITTED");
        update.put("claimDocumentUrl", null);
        insuranceMapper.updateClaim(update);

        return toResponse(insuranceMapper.findClaimById(claimId));
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
     * 저장 키에 붙일 확장자를 뽑는다.
     *
     * <p>S3는 업로드 시 지정한 Content-Type을 그대로 응답에 실어 주고, 그 판정은 키의
     * 확장자로 한다. 확장자를 잃으면 브라우저가 영수증 이미지를 표시하지 않고 내려받는다.
     */
    private String extensionOf(String originalFilename) {
        if (originalFilename == null || !originalFilename.contains(".")) {
            return "jpg";
        }
        return originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
    }
}
