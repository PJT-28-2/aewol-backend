package com.aewol.domain.insurance.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.insurance.dto.ClaimResponse;
import com.aewol.domain.insurance.mapper.InsuranceMapper;
import com.aewol.external.gemini.GeminiVisionClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimServiceImplTest {

    @Mock InsuranceMapper insuranceMapper;
    @Mock GeminiVisionClient geminiVisionClient;

    private ClaimServiceImpl service;

    private Map<String, Object> claimRow(long claimId, long memberId, long petId, String hospitalName,
                                          String treatmentDate, BigDecimal totalAmount, String extractedData,
                                          String claimStatus) {
        Map<String, Object> row = new HashMap<>();
        row.put("claim_id", claimId);
        row.put("member_id", memberId);
        row.put("pet_id", petId);
        row.put("hospital_name", hospitalName);
        row.put("treatment_date", treatmentDate);
        row.put("total_amount", totalAmount);
        row.put("claim_status", claimStatus);
        row.put("claim_document_url", null);
        row.put("receipt_image_url", "/uploads/receipts/x.jpg");
        row.put("extracted_data", extractedData);
        return row;
    }

    // ---------- createClaim ----------

    @Test
    @DisplayName("createClaim은 OCR 결과를 extractedData로 저장하고 hospitalName 등은 null로 초기화한다")
    void should_createDraftClaim_withNullFieldsAndExtractedData(@TempDir Path tempDir) {
        service = new ClaimServiceImpl(insuranceMapper, geminiVisionClient);
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());

        when(geminiVisionClient.extractReceiptData(any(), anyString()))
                .thenReturn("{\"hospital_name\":\"애월동물병원\"}");
        doAnswer(invocation -> {
            Map<String, Object> claim = invocation.getArgument(0);
            claim.put("claimId", 1L);
            return null;
        }).when(insuranceMapper).insertClaim(any());
        when(insuranceMapper.findClaimById("1")).thenReturn(
                claimRow(1L, 100L, 10L, null, null, null, "{\"hospital_name\":\"애월동물병원\"}", "DRAFT"));

        MockMultipartFile receipt = new MockMultipartFile("receipt", "receipt.jpg", "image/jpeg", "img".getBytes());

        ClaimResponse result = service.createClaim("100", "10", receipt);

        assertEquals("1", result.getClaimId());
        assertEquals("DRAFT", result.getClaimStatus());
        assertEquals("{\"hospital_name\":\"애월동물병원\"}", result.getExtractedData());
        assertNull(result.getHospitalName());
        verify(insuranceMapper).insertClaim(argThat((Map<String, Object> m) ->
                "DRAFT".equals(m.get("claimStatus")) && m.get("hospitalName") == null));
    }

    // ---------- confirmClaim ----------

    @Test
    @DisplayName("confirmClaim은 본인 소유 청구에 수정 데이터를 반영하고 상태를 SUBMITTED로 전이한다")
    void should_updateClaim_whenOwnerConfirmsWithCorrectedData() {
        service = new ClaimServiceImpl(insuranceMapper, geminiVisionClient);
        when(insuranceMapper.findClaimById("1"))
                .thenReturn(claimRow(1L, 100L, 10L, null, null, null, "{}", "DRAFT"))
                .thenReturn(claimRow(1L, 100L, 10L, "애월동물병원", "2026-01-01", new BigDecimal("15000"), "{}", "SUBMITTED"));

        ClaimResponse corrected = ClaimResponse.builder()
                .hospitalName("애월동물병원")
                .treatmentDate("2026-01-01")
                .totalAmount(new BigDecimal("15000"))
                .build();

        ClaimResponse result = service.confirmClaim("100", "1", corrected);

        assertEquals("SUBMITTED", result.getClaimStatus());
        assertEquals("애월동물병원", result.getHospitalName());
        verify(insuranceMapper).updateClaim(argThat((Map<String, Object> m) ->
                "애월동물병원".equals(m.get("hospitalName")) && "SUBMITTED".equals(m.get("claimStatus"))));
    }

    @Test
    @DisplayName("confirmClaim은 body가 없으면(null) 기존 4개 필드를 그대로 유지한 채 상태만 전이한다")
    void should_preserveAllFourFields_whenConfirmedWithoutBody() {
        service = new ClaimServiceImpl(insuranceMapper, geminiVisionClient);
        Map<String, Object> existing = claimRow(1L, 100L, 10L, "기존병원", "2025-12-01",
                new BigDecimal("9000"), "{\"hospital_name\":\"기존병원\"}", "DRAFT");
        when(insuranceMapper.findClaimById("1")).thenReturn(existing);

        service.confirmClaim("100", "1", null);

        verify(insuranceMapper).updateClaim(argThat((Map<String, Object> m) ->
                "기존병원".equals(m.get("hospitalName"))
                        && "2025-12-01".equals(m.get("treatmentDate"))
                        && new BigDecimal("9000").equals(m.get("totalAmount"))
                        && "{\"hospital_name\":\"기존병원\"}".equals(m.get("extractedData"))
                        && "SUBMITTED".equals(m.get("claimStatus"))));
    }

    @Test
    @DisplayName("confirmClaim은 타인의 청구를 조회하면 not-found 예외를 던진다")
    void should_throwNotFound_whenConfirmingOthersClaim() {
        service = new ClaimServiceImpl(insuranceMapper, geminiVisionClient);
        when(insuranceMapper.findClaimById("1")).thenReturn(claimRow(1L, 999L, 10L, null, null, null, "{}", "DRAFT"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmClaim("100", "1", null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("confirmClaim은 존재하지 않는 claimId에 대해 not-found 예외를 던진다")
    void should_throwNotFound_whenConfirmingNonExistentClaim() {
        service = new ClaimServiceImpl(insuranceMapper, geminiVisionClient);
        when(insuranceMapper.findClaimById("999")).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.confirmClaim("100", "999", null));
    }

    // ---------- getClaims ----------

    @Test
    @DisplayName("getClaims는 회원의 청구 목록을 ClaimResponse 리스트로 변환한다")
    void should_returnClaimList_forMember() {
        service = new ClaimServiceImpl(insuranceMapper, geminiVisionClient);
        when(insuranceMapper.findClaimsByMemberId("100")).thenReturn(List.of(
                claimRow(1L, 100L, 10L, "병원A", "2026-01-01", new BigDecimal("1000"), "{}", "SUBMITTED"),
                claimRow(2L, 100L, 10L, null, null, null, "{}", "DRAFT")));

        List<ClaimResponse> result = service.getClaims("100");

        assertEquals(2, result.size());
        assertEquals("1", result.get(0).getClaimId());
        assertEquals("2", result.get(1).getClaimId());
    }

    // ---------- getClaim ----------

    @Test
    @DisplayName("getClaim은 본인 소유 청구를 정상 조회한다")
    void should_returnClaim_whenOwnerRequestsOwnClaim() {
        service = new ClaimServiceImpl(insuranceMapper, geminiVisionClient);
        when(insuranceMapper.findClaimById("1")).thenReturn(
                claimRow(1L, 100L, 10L, "병원A", "2026-01-01", new BigDecimal("1000"), "{}", "SUBMITTED"));

        ClaimResponse result = service.getClaim("100", "1");

        assertEquals("1", result.getClaimId());
        assertEquals("10", result.getPetId());
    }

    @Test
    @DisplayName("getClaim은 타인의 청구를 조회하면 not-found 예외를 던진다")
    void should_throwNotFound_whenRequestingOthersClaim() {
        service = new ClaimServiceImpl(insuranceMapper, geminiVisionClient);
        when(insuranceMapper.findClaimById("1")).thenReturn(claimRow(1L, 999L, 10L, null, null, null, "{}", "DRAFT"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.getClaim("100", "1"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("getClaim은 존재하지 않는 claimId에 대해 NPE 대신 not-found 예외를 던진다")
    void should_throwNotFound_whenClaimDoesNotExist() {
        service = new ClaimServiceImpl(insuranceMapper, geminiVisionClient);
        when(insuranceMapper.findClaimById("999")).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.getClaim("100", "999"));
    }
}
