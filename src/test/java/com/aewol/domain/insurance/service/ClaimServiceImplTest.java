package com.aewol.domain.insurance.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.storage.FileStorage;
import com.aewol.domain.insurance.dto.ClaimConfirmRequest;
import com.aewol.domain.insurance.dto.ClaimResponse;
import com.aewol.domain.insurance.mapper.InsuranceMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.external.paddleocr.PaddleOcrClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimServiceImplTest {

    @Mock InsuranceMapper insuranceMapper;
    @Mock PetMapper petMapper;
    @Mock PaddleOcrClient paddleOcrClient;
    @Mock FileStorage fileStorage;

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
    void should_createDraftClaim_withNullFieldsAndExtractedData() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(petMapper.findByIdAndMemberId("10", "100")).thenReturn(Map.of("pet_id", "10"));
        when(fileStorage.store(any(), eq("receipts"), eq("jpg"))).thenReturn("receipts/x.jpg");

        when(paddleOcrClient.extractReceiptData(any(), anyString()))
                .thenReturn("{\"hospital_name\":\"애월동물병원\"}");
        doAnswer(invocation -> {
            Map<String, Object> claim = invocation.getArgument(0);
            claim.put("claimId", 1L);
            return null;
        }).when(insuranceMapper).insertClaim(any());
        when(insuranceMapper.findClaimById("1")).thenReturn(
                claimRow(1L, 100L, 10L, null, null, null, "{\"hospital_name\":\"애월동물병원\"}", "DRAFT"));

        ClaimResponse result = service.createClaim("100", "10", jpegReceipt());

        assertEquals("1", result.getClaimId());
        assertEquals("DRAFT", result.getClaimStatus());
        assertEquals("{\"hospital_name\":\"애월동물병원\"}", result.getExtractedData());
        assertNull(result.getHospitalName());
        verify(insuranceMapper).insertClaim(argThat((Map<String, Object> m) ->
                "DRAFT".equals(m.get("claimStatus")) && m.get("hospitalName") == null));
        verify(fileStorage, never()).delete(anyString());
    }

    @Test
    @DisplayName("createClaim은 DB 저장이 실패하면 올려 둔 영수증 파일을 지운다")
    void should_deleteStoredReceipt_whenInsertClaimFails() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(petMapper.findByIdAndMemberId("10", "100")).thenReturn(Map.of("pet_id", "10"));
        when(fileStorage.store(any(), eq("receipts"), eq("jpg"))).thenReturn("receipts/x.jpg");
        when(paddleOcrClient.extractReceiptData(any(), anyString())).thenReturn("{}");
        doThrow(new RuntimeException("db")).when(insuranceMapper).insertClaim(any());

        assertThrows(RuntimeException.class, () -> service.createClaim("100", "10", jpegReceipt()));
        verify(fileStorage).delete("receipts/x.jpg");
    }

    /*
     * insert가 지나가면 청구 행은 이미 커밋돼 있다. createClaim은 OCR을 트랜잭션 밖에서
     * 부르려고 @Transactional을 붙이지 않았으므로, 뒤에서 무엇이 실패하든 행은 남는다.
     * 그때 영수증까지 지우면 멀쩡히 만들어진 청구가 깨진 이미지를 가리키게 된다.
     */
    @Test
    @DisplayName("createClaim은 저장이 끝난 뒤에 실패하면 영수증을 지우지 않는다")
    void should_keepStoredReceipt_whenFailureHappensAfterInsert() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(petMapper.findByIdAndMemberId("10", "100")).thenReturn(Map.of("pet_id", "10"));
        when(fileStorage.store(any(), eq("receipts"), eq("jpg"))).thenReturn("receipts/x.jpg");
        when(paddleOcrClient.extractReceiptData(any(), anyString())).thenReturn("{}");
        doAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("claimId", 1);
            return 1;
        }).when(insuranceMapper).insertClaim(any());
        // 저장은 끝났는데 되돌려줄 값을 읽는 데서 넘어진 상황
        when(insuranceMapper.findClaimById("1")).thenThrow(new RuntimeException("조회 실패"));

        assertThrows(RuntimeException.class, () -> service.createClaim("100", "10", jpegReceipt()));

        verify(insuranceMapper).insertClaim(any());
        verify(fileStorage, never()).delete(anyString());
    }

    @Test
    @DisplayName("createClaim은 타인 반려동물이면 영수증을 저장하기 전에 거절한다")
    void should_rejectCreateClaim_whenPetDoesNotBelongToMember() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(petMapper.findByIdAndMemberId("10", "100")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createClaim("100", "10", jpegReceipt()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(fileStorage, never()).store(any(), anyString(), anyString());
        verify(paddleOcrClient, never()).extractReceiptData(any(), anyString());
        verify(insuranceMapper, never()).insertClaim(any());
    }

    @Test
    @DisplayName("createClaim은 빈 영수증을 저장하지 않는다")
    void should_rejectCreateClaim_whenReceiptIsEmpty() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(petMapper.findByIdAndMemberId("10", "100")).thenReturn(Map.of("pet_id", "10"));
        MockMultipartFile empty = new MockMultipartFile("receipt", "receipt.jpg", "image/jpeg", new byte[0]);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createClaim("100", "10", empty));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(fileStorage, never()).store(any(), anyString(), anyString());
        verify(paddleOcrClient, never()).extractReceiptData(any(), anyString());
    }

    @Test
    @DisplayName("createClaim은 10MB를 넘는 영수증을 저장하지 않는다")
    void should_rejectCreateClaim_whenReceiptExceedsMaxSize() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(petMapper.findByIdAndMemberId("10", "100")).thenReturn(Map.of("pet_id", "10"));
        MultipartFile huge = mock(MultipartFile.class);
        when(huge.isEmpty()).thenReturn(false);
        when(huge.getSize()).thenReturn(10L * 1024 * 1024 + 1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createClaim("100", "10", huge));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(fileStorage, never()).store(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("createClaim은 허용하지 않는 MIME 타입을 거절한다")
    void should_rejectCreateClaim_whenContentTypeIsNotAllowed() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(petMapper.findByIdAndMemberId("10", "100")).thenReturn(Map.of("pet_id", "10"));
        MockMultipartFile svg = new MockMultipartFile("receipt", "a.svg", "image/svg+xml", jpegBytes());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createClaim("100", "10", svg));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(fileStorage, never()).store(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("createClaim은 확장자를 속인 비이미지 파일을 저장하지 않는다")
    void should_rejectCreateClaim_whenFileSignatureIsNotImage() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(petMapper.findByIdAndMemberId("10", "100")).thenReturn(Map.of("pet_id", "10"));
        MockMultipartFile fake = new MockMultipartFile("receipt", "receipt.jpg", "image/jpeg",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>".getBytes());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createClaim("100", "10", fake));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(fileStorage, never()).store(any(), anyString(), anyString());
        verify(paddleOcrClient, never()).extractReceiptData(any(), anyString());
    }

    @Test
    @DisplayName("createClaim은 확장자를 파일명이 아니라 실제 내용에서 정한다")
    void should_storeReceiptExtensionFromContent_notFilename() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(petMapper.findByIdAndMemberId("10", "100")).thenReturn(Map.of("pet_id", "10"));
        when(fileStorage.store(any(), eq("receipts"), eq("png"))).thenReturn("receipts/x.png");
        when(paddleOcrClient.extractReceiptData(any(), anyString())).thenReturn("{}");
        doAnswer(invocation -> {
            Map<String, Object> claim = invocation.getArgument(0);
            claim.put("claimId", 1L);
            return null;
        }).when(insuranceMapper).insertClaim(any());
        when(insuranceMapper.findClaimById("1")).thenReturn(
                claimRow(1L, 100L, 10L, null, null, null, "{}", "DRAFT"));
        MockMultipartFile mislabeled = new MockMultipartFile(
                "receipt", "receipt.jpg", "image/jpeg", pngBytes());

        service.createClaim("100", "10", mislabeled);

        verify(fileStorage).store(any(), eq("receipts"), eq("png"));
    }

    // ---------- confirmClaim ----------

    @Test
    @DisplayName("confirmClaim은 본인 소유 청구에 수정 데이터를 반영하고 상태를 SUBMITTED로 전이한다")
    void should_updateClaim_whenOwnerConfirmsWithCorrectedData() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(insuranceMapper.findClaimById("1"))
                .thenReturn(claimRow(1L, 100L, 10L, null, null, null, "{}", "DRAFT"))
                .thenReturn(claimRow(1L, 100L, 10L, "애월동물병원", "2026-01-01", new BigDecimal("15000"), "{}", "SUBMITTED"));

        ClaimConfirmRequest corrected = ClaimConfirmRequest.builder()
                .hospitalName("애월동물병원")
                .treatmentDate(LocalDate.of(2026, 1, 1))
                .totalAmount(new BigDecimal("15000"))
                .build();

        ClaimResponse result = service.confirmClaim("100", "1", corrected);

        assertEquals("SUBMITTED", result.getClaimStatus());
        assertEquals("애월동물병원", result.getHospitalName());
        verify(insuranceMapper).updateClaim(argThat((Map<String, Object> m) ->
                "애월동물병원".equals(m.get("hospitalName"))
                        && LocalDate.of(2026, 1, 1).equals(m.get("treatmentDate"))
                        && "SUBMITTED".equals(m.get("claimStatus"))));
    }

    @Test
    @DisplayName("confirmClaim은 body가 없으면(null) 기존 4개 필드를 그대로 유지한 채 상태만 전이한다")
    void should_preserveAllFourFields_whenConfirmedWithoutBody() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
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
    @DisplayName("confirmClaim은 body가 없어도 기존 필수값이 비어 있으면 제출하지 않는다")
    void should_rejectIncompleteExistingClaim_whenConfirmedWithoutBody() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(insuranceMapper.findClaimById("1"))
                .thenReturn(claimRow(1L, 100L, 10L, null, "2025-12-01",
                        new BigDecimal("9000"), "{}", "DRAFT"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirmClaim("100", "1", null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(insuranceMapper, never()).updateClaim(any());
    }

    @Test
    @DisplayName("confirmClaim은 타인의 청구를 조회하면 not-found 예외를 던진다")
    void should_throwNotFound_whenConfirmingOthersClaim() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(insuranceMapper.findClaimById("1")).thenReturn(claimRow(1L, 999L, 10L, null, null, null, "{}", "DRAFT"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmClaim("100", "1", null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("confirmClaim은 존재하지 않는 claimId에 대해 not-found 예외를 던진다")
    void should_throwNotFound_whenConfirmingNonExistentClaim() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(insuranceMapper.findClaimById("999")).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.confirmClaim("100", "999", null));
    }

    @Test
    @DisplayName("confirmClaim은 DRAFT가 아니면 재제출하지 않는다")
    void should_rejectConfirmClaim_whenStatusIsNotDraft() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(insuranceMapper.findClaimById("1")).thenReturn(
                claimRow(1L, 100L, 10L, "애월동물병원", "2026-01-01", new BigDecimal("15000"), "{}", "SUBMITTED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmClaim("100", "1", ClaimConfirmRequest.builder()
                        .hospitalName("다른병원")
                        .treatmentDate(LocalDate.of(2026, 1, 2))
                        .totalAmount(new BigDecimal("1"))
                        .build()));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(insuranceMapper, never()).updateClaim(any());
    }

    // ---------- getClaims ----------

    @Test
    @DisplayName("getClaims는 회원의 청구 목록을 ClaimResponse 리스트로 변환한다")
    void should_returnClaimList_forMember() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
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
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(insuranceMapper.findClaimById("1")).thenReturn(
                claimRow(1L, 100L, 10L, "병원A", "2026-01-01", new BigDecimal("1000"), "{}", "SUBMITTED"));
        when(fileStorage.signedUrl("/uploads/receipts/x.jpg")).thenReturn("signed:receipts/x.jpg");

        ClaimResponse result = service.getClaim("100", "1");

        assertEquals("1", result.getClaimId());
        assertEquals("10", result.getPetId());
        assertEquals("signed:receipts/x.jpg", result.getReceiptImageUrl());
    }

    @Test
    @DisplayName("getClaim은 타인의 청구를 조회하면 not-found 예외를 던진다")
    void should_throwNotFound_whenRequestingOthersClaim() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(insuranceMapper.findClaimById("1")).thenReturn(claimRow(1L, 999L, 10L, null, null, null, "{}", "DRAFT"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.getClaim("100", "1"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("getClaim은 존재하지 않는 claimId에 대해 NPE 대신 not-found 예외를 던진다")
    void should_throwNotFound_whenClaimDoesNotExist() {
        service = new ClaimServiceImpl(insuranceMapper, petMapper, paddleOcrClient, fileStorage);
        when(insuranceMapper.findClaimById("999")).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.getClaim("100", "999"));
    }

    private static MockMultipartFile jpegReceipt() {
        return new MockMultipartFile("receipt", "receipt.jpg", "image/jpeg", jpegBytes());
    }

    private static byte[] jpegBytes() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2};
    }

    private static byte[] pngBytes() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    }
}
