package com.aewol.domain.insurance.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.insurance.dto.ClaimResponse;
import com.aewol.domain.insurance.mapper.InsuranceMapper;
import com.aewol.external.gemini.GeminiVisionClient;
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
    private final GeminiVisionClient geminiVisionClient;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    @Override
    @Transactional
    public ClaimResponse createClaim(String memberId, String petId, MultipartFile receipt) {
        try {
            Path dir = Paths.get(uploadDir, "receipts");
            Files.createDirectories(dir);
            String filename = UUID.randomUUID() + "_" + receipt.getOriginalFilename();
            Path filepath = dir.resolve(filename);
            Files.write(filepath, receipt.getBytes());

            String imageUrl = "/uploads/receipts/" + filename;

            // Gemini Vision OCR
            String extractedJson = geminiVisionClient.extractReceiptData(
                    receipt.getBytes(), receipt.getContentType());

            String claimId = UUID.randomUUID().toString();
            Map<String, Object> claim = new HashMap<>();
            claim.put("claimId", claimId);
            claim.put("petId", petId);
            claim.put("memberId", memberId);
            claim.put("receiptImageUrl", imageUrl);
            claim.put("extractedData", extractedJson);
            claim.put("hospitalName", null);
            claim.put("treatmentDate", null);
            claim.put("totalAmount", null);
            claim.put("claimStatus", "DRAFT");
            insuranceMapper.insertClaim(claim);

            return toResponse(insuranceMapper.findClaimById(claimId));
        } catch (IOException e) {
            throw new BusinessException("영수증 업로드에 실패했습니다.");
        }
    }

    @Override
    @Transactional
    public ClaimResponse confirmClaim(String claimId, ClaimResponse correctedData) {
        Map<String, Object> existing = insuranceMapper.findClaimById(claimId);
        if (existing == null) {
            throw BusinessException.notFound("청구 정보를 찾을 수 없습니다.");
        }

        Map<String, Object> update = new HashMap<>();
        update.put("claimId", claimId);
        update.put("hospitalName", correctedData.getHospitalName());
        update.put("treatmentDate", correctedData.getTreatmentDate());
        update.put("totalAmount", correctedData.getTotalAmount());
        update.put("claimStatus", "SUBMITTED");
        update.put("claimDocumentUrl", null);
        update.put("extractedData", existing.get("extracted_data"));
        insuranceMapper.updateClaim(update);

        return toResponse(insuranceMapper.findClaimById(claimId));
    }

    @Override
    public List<ClaimResponse> getClaims(String memberId) {
        return insuranceMapper.findClaimsByMemberId(memberId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private ClaimResponse toResponse(Map<String, Object> claim) {
        return ClaimResponse.builder()
                .claimId((String) claim.get("claim_id"))
                .petId((String) claim.get("pet_id"))
                .hospitalName((String) claim.get("hospital_name"))
                .treatmentDate(claim.get("treatment_date") != null ? claim.get("treatment_date").toString() : null)
                .totalAmount(claim.get("total_amount") != null ? (BigDecimal) claim.get("total_amount") : null)
                .claimStatus((String) claim.get("claim_status"))
                .claimDocumentUrl((String) claim.get("claim_document_url"))
                .receiptImageUrl((String) claim.get("receipt_image_url"))
                .extractedData(claim.get("extracted_data"))
                .build();
    }
}
