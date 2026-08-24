package com.aewol.domain.pet.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.storage.FileStorage;
import com.aewol.domain.insurance.mapper.InsuranceMapper;
import com.aewol.domain.pet.dto.PetCreateRequest;
import com.aewol.domain.pet.dto.PetResponse;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.pet.mapper.PetDocumentMapper;
import com.aewol.domain.recurring.mapper.RecurringMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class PetServiceImplTest {

    @Mock PetMapper petMapper;
    @Mock PetDocumentMapper petDocumentMapper;    @Mock FileStorage fileStorage;
    @Mock PetRegistrationService petRegistrationService;
    @Mock InsuranceMapper insuranceMapper;
    @Mock RecurringMapper recurringMapper;

    private PetServiceImpl service() {
        return new PetServiceImpl(petMapper, petDocumentMapper, fileStorage, petRegistrationService,
                insuranceMapper, recurringMapper);
    }

    @Test
    void should_returnPet_when_memberOwnsPet() {
        PetServiceImpl service = service();
        Map<String, Object> pet = pet("member-1");
        pet.put("profile_img", "pet-character/profile.png");
        when(petMapper.findById("pet-1")).thenReturn(pet);
        when(fileStorage.signedUrl("pet-character/profile.png"))
                .thenReturn("/api/files/pet-character/profile.png?signed");

        assertEquals("pet-1", service.getPet("member-1", "pet-1").getPetId());
        assertEquals("/api/files/pet-character/profile.png?signed",
                service.getPet("member-1", "pet-1").getProfileImg());
    }

    // AI 캐릭터는 처음부터 용도가 둘이다. profile_img는 얼굴 클로즈업(프로필용),
    // character_img는 전신(홈 화면 히어로용)이다. 응답에 전신이 빠져 있어 프론트가
    // 홈 캐릭터를 받을 방법이 없었다(#141).
    @Test
    void should_returnBothCharacterImages_when_petHasGeneratedCharacter() {
        PetServiceImpl service = service();
        Map<String, Object> pet = pet("member-1");
        pet.put("profile_img", "pet-character/profile.png");
        pet.put("character_img", "pet-character/fullbody.png");
        when(petMapper.findById("pet-1")).thenReturn(pet);
        when(fileStorage.signedUrl("pet-character/profile.png"))
                .thenReturn("/api/files/pet-character/profile.png?signed");
        when(fileStorage.signedUrl("pet-character/fullbody.png"))
                .thenReturn("/api/files/pet-character/fullbody.png?signed");

        PetResponse response = service.getPet("member-1", "pet-1");

        assertEquals("/api/files/pet-character/profile.png?signed", response.getProfileImg());
        assertEquals("/api/files/pet-character/fullbody.png?signed", response.getCharacterImg());
    }

    // 공동육아 초대를 수락한 구성원(shared_access ACCEPTED)은 소유자가 아니어도
    // 반려동물 상세를 조회할 수 있어야 한다.
    @Test
    void should_returnPet_when_memberHasSharedAccess() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-1")).thenReturn(pet("owner-1"));
        when(petMapper.hasSharedAccess("pet-1", "member-2")).thenReturn(true);

        assertEquals("pet-1", service.getPet("member-2", "pet-1").getPetId());
    }

    @Test
    void should_throwForbidden_when_memberCannotViewPet() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-1")).thenReturn(pet("owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getPet("member-2", "pet-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    @Test
    void should_throwNotFound_when_petDoesNotExist() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getPet("member-1", "pet-404"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void should_throwForbidden_when_nonOwnerUpdatesPet() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-1")).thenReturn(pet("owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updatePet("member-2", "pet-1", null));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verify(petMapper, never()).update(any());
    }

    @Test
    void should_updatePet_when_memberOwnsPet() {
        PetServiceImpl service = service();
        PetCreateRequest request = mock(PetCreateRequest.class);
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));

        service.updatePet("member-1", "pet-1", request);

        verify(petMapper).update(argThat(row ->
                "pet-1".equals(row.get("petId")) && !row.containsKey("regNumber")));
    }

    // 등록 시에도 저장돼야 한다. insert SQL에 컬럼이 없어 API는 성공하는데 값만 조용히
    // 버려지던 문제가 있었다.
    @Test
    @DisplayName("등록할 때도 인스타 핸들을 저장한다")
    void should_saveInstagramHandle_onCreate() {
        PetServiceImpl service = service();
        PetCreateRequest request = mock(PetCreateRequest.class);
        when(request.getInstagramId()).thenReturn("@Bori_Daily");

        // createPet은 insert 후 getPet으로 되돌려주므로 조회까지 열어 둔다.
        doAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("petId", "pet-1");
            return null;
        }).when(petMapper).insert(any());
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));

        service.createPet("member-1", request);

        verify(petMapper).insert(argThat(row -> "bori_daily".equals(row.get("instagramId"))));
    }

    // 인스타 계정 없이 등록하는 쪽이 오히려 보통이다. 정규화가 null에서 넘어지면
    // 반려동물 등록 자체가 막힌다.
    @Test
    @DisplayName("인스타 핸들 없이도 등록된다")
    void should_createPet_when_instagramHandleMissing() {
        PetServiceImpl service = service();
        PetCreateRequest request = mock(PetCreateRequest.class);
        when(request.getInstagramId()).thenReturn(null);

        doAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("petId", "pet-1");
            return null;
        }).when(petMapper).insert(any());
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));

        assertDoesNotThrow(() -> service.createPet("member-1", request));

        verify(petMapper).insert(argThat(row -> row.get("instagramId") == null));
    }

    // 인스타그램 핸들은 대소문자를 구분하지 않는다. 저장 형태가 들쭉날쭉하면 같은 계정이
    // 링크마다 다르게 보인다.
    @Test
    @DisplayName("인스타 핸들은 @를 떼고 소문자로 저장한다")
    void should_normalizeInstagramHandle() {
        PetServiceImpl service = service();
        PetCreateRequest request = mock(PetCreateRequest.class);
        when(request.getInstagramId()).thenReturn("@Bori_Daily");
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));

        service.updatePet("member-1", "pet-1", request);

        verify(petMapper).update(argThat(row -> "bori_daily".equals(row.get("instagramId"))));
    }

    // 지우려고 비운 것과 처음부터 없는 것을 DB에서 같게 둔다.
    @Test
    @DisplayName("빈 값으로 보내면 핸들을 지운다")
    void should_clearInstagramHandle_when_blank() {
        PetServiceImpl service = service();
        PetCreateRequest request = mock(PetCreateRequest.class);
        when(request.getInstagramId()).thenReturn("   ");
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));

        service.updatePet("member-1", "pet-1", request);

        verify(petMapper).update(argThat(row -> row.get("instagramId") == null));
    }

    @Test
    void should_verifyRegistration_when_registrationNumberIsAdded() {
        PetServiceImpl service = service();
        PetCreateRequest request = mock(PetCreateRequest.class);
        when(request.getRegNumber()).thenReturn("410000012345678");
        when(request.getRegistrationOwnerName()).thenReturn("홍길동");
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));

        service.updatePet("member-1", "pet-1", request);

        verify(petRegistrationService).verify(eq("member-1"), eq("pet-1"),
                argThat(verifyRequest ->
                        "410000012345678".equals(verifyRequest.getRegNumber())
                                && "홍길동".equals(verifyRequest.getUserName())));
    }

    @Test
    void should_reverifyExistingRegistration_when_identityChangesAndNumberIsOmitted() {
        PetServiceImpl service = service();
        PetCreateRequest request = mock(PetCreateRequest.class);
        Map<String, Object> existing = pet("member-1");
        existing.put("reg_number", "410000012345678");
        existing.put("birth_date", "2020-01-01");
        when(request.getName()).thenReturn("바뀐이름");
        when(request.getBirthDate()).thenReturn("2020-01-01");
        when(petMapper.findById("pet-1")).thenReturn(existing);

        service.updatePet("member-1", "pet-1", request);

        verify(petRegistrationService).verify(eq("member-1"), eq("pet-1"),
                argThat(verifyRequest -> "410000012345678".equals(verifyRequest.getRegNumber())));
    }

    @Test
    void should_reverifyExistingRegistration_when_ownerNameIsProvided() {
        PetServiceImpl service = service();
        PetCreateRequest request = mock(PetCreateRequest.class);
        Map<String, Object> existing = pet("member-1");
        existing.put("reg_number", "410000012345678");
        when(request.getRegNumber()).thenReturn("410000012345678");
        when(request.getRegistrationOwnerName()).thenReturn("새소유자");
        when(request.getName()).thenReturn("애월");
        when(petMapper.findById("pet-1")).thenReturn(existing);

        service.updatePet("member-1", "pet-1", request);

        verify(petRegistrationService).verify(eq("member-1"), eq("pet-1"),
                argThat(verifyRequest ->
                        "410000012345678".equals(verifyRequest.getRegNumber())
                                && "새소유자".equals(verifyRequest.getUserName())));
    }

    @Test
    void should_notDisconnectRegistration_when_registrationDoesNotExist() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("pet-1", "REGISTRATION"))
                .thenReturn(null);

        assertThrows(BusinessException.class,
                () -> service.disconnectRegistration("member-1", "pet-1"));

        verify(petRegistrationService, never()).cancel(anyString(), anyString(), anyString());
    }

    @Test
    void should_disconnectRegistrationAndDocument_when_registrationExists() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("pet-1", "REGISTRATION"))
                .thenReturn(map("doc_id", "doc-1", "pet_id", "pet-1"));
        when(petDocumentMapper.deleteByIdAndPetId("doc-1", "pet-1")).thenReturn(1);

        service.disconnectRegistration("member-1", "pet-1");

        verify(petRegistrationService).cancel("member-1", "pet-1", "doc-1");
        verify(petDocumentMapper).deleteByIdAndPetId("doc-1", "pet-1");
    }

    @Test
    void should_throwNotFound_when_updatingMissingPet() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updatePet("member-1", "pet-404", mock(PetCreateRequest.class)));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verify(petMapper, never()).update(any());
    }

    @Test
    void should_throwForbidden_when_nonOwnerDeletesPet() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-1")).thenReturn(pet("owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.deactivatePet("member-2", "pet-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verify(petMapper, never()).deactivate(anyString(), anyString());
    }

    @Test
    void should_deactivatePet_when_memberOwnsPet() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        when(petDocumentMapper.findByPetId("pet-1")).thenReturn(List.of());
        when(petMapper.updateRegistrationNumber("pet-1", "member-1", null)).thenReturn(1);
        when(petMapper.deactivate("pet-1", "member-1")).thenReturn(1);

        service.deactivatePet("member-1", "pet-1");

        verify(petMapper).deactivate("pet-1", "member-1");
    }

    // #291: pet 행은 남기고 소유자 개인 데이터(등록증/문서/보험/정기결제)만 하드 삭제하며,
    // reg_number를 null로 초기화한다. care_diary/shared_access는 이번 변경으로 손대지 않는다.
    @Test
    void should_deleteOwnerPersonalDataAndClearRegNumber_when_deactivatingPet() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        Map<String, Object> registrationDoc = new HashMap<>();
        registrationDoc.put("doc_id", "doc-1");
        registrationDoc.put("doc_type", "REGISTRATION");
        registrationDoc.put("file_url", null);
        Map<String, Object> vaccinationDoc = new HashMap<>();
        vaccinationDoc.put("doc_id", "doc-2");
        vaccinationDoc.put("doc_type", "VACCINATION");
        vaccinationDoc.put("file_url", "pet-documents/vaccine.jpg");
        when(petDocumentMapper.findByPetId("pet-1")).thenReturn(List.of(registrationDoc, vaccinationDoc));
        when(petDocumentMapper.findByIdAndPetIdForUpdate("doc-1", "pet-1")).thenReturn(registrationDoc);
        when(petDocumentMapper.findByIdAndPetIdForUpdate("doc-2", "pet-1")).thenReturn(vaccinationDoc);
        when(petDocumentMapper.deleteByIdAndPetId("doc-1", "pet-1")).thenReturn(1);
        when(petDocumentMapper.deleteByIdAndPetId("doc-2", "pet-1")).thenReturn(1);
        Map<String, Object> claim = new HashMap<>();
        claim.put("claim_id", "claim-1");
        claim.put("receipt_image_url", "receipts/claim-1.jpg");
        claim.put("claim_document_url", "claim-documents/claim-1.pdf");
        when(insuranceMapper.findClaimsByPetId("pet-1")).thenReturn(List.of(claim));
        when(petMapper.updateRegistrationNumber("pet-1", "member-1", null)).thenReturn(1);
        when(petMapper.deactivate("pet-1", "member-1")).thenReturn(1);

        service.deactivatePet("member-1", "pet-1");

        verify(petRegistrationService).cancel("member-1", "pet-1", "doc-1");
        verify(petRegistrationService, never()).cancel(eq("member-1"), eq("pet-1"), eq("doc-2"));
        verify(petDocumentMapper).deleteByIdAndPetId("doc-1", "pet-1");
        verify(petDocumentMapper).deleteByIdAndPetId("doc-2", "pet-1");
        // 보험 청구는 pet_document와 달리 첨부 파일 키를 자체 컬럼에 들고 있어서,
        // 행 삭제와 별개로 파일 정리도 함께 이뤄져야 한다.
        verify(fileStorage).delete("receipts/claim-1.jpg");
        verify(fileStorage).delete("claim-documents/claim-1.pdf");
        verify(insuranceMapper).deleteClaimsByPetId("pet-1");
        verify(insuranceMapper).deleteSimulationsByPetId("pet-1");
        verify(recurringMapper).deactivateByPetId("pet-1");
        verify(petMapper).updateRegistrationNumber("pet-1", "member-1", null);
        verify(petMapper).deactivate("pet-1", "member-1");
    }

    // WEBP는 "RIFF"(0~3바이트) + 크기(4~7바이트) + "WEBP"(8~11바이트) 구조라, 확장자만 보고
    // 통과시키면 안 되고 이 헤더까지 실제로 일치해야 한다(#372).
    @Test
    void should_uploadDocument_when_extensionAndSignatureAreWebp() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        byte[] webpHeader = {
                0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50
        };
        MockMultipartFile file = new MockMultipartFile("file", "vaccination.webp", "image/webp", webpHeader);
        when(fileStorage.store(eq(webpHeader), eq("pet-documents"), eq("webp")))
                .thenReturn("pet-documents/vaccination.webp");

        assertDoesNotThrow(() ->
                service.uploadPetDocument("member-1", "pet-1", "VACCINATION", file, null));

        verify(fileStorage).store(webpHeader, "pet-documents", "webp");
    }

    @Test
    void should_rejectDocument_when_extensionIsWebpButSignatureDoesNotMatch() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        // 확장자만 .webp이고 실제 내용은 JPEG 시그니처인 위조 파일.
        byte[] fakeContent = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
        MockMultipartFile file = new MockMultipartFile("file", "vaccination.webp", "image/webp", fakeContent);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadPetDocument("member-1", "pet-1", "VACCINATION", file, null));

        assertEquals("JPEG, PNG, WEBP, PDF 파일만 업로드할 수 있습니다.", exception.getMessage());
        verify(fileStorage, never()).store(any(), any(), any());
    }

    // "RIFF" 프리픽스와 길이는 충분하지만 8~11바이트가 "WEBP"가 아닌 다른 RIFF 기반 포맷(AVI는
    // 실제로 RIFF 컨테이너를 공유한다)으로 위장한 파일 — 위 테스트(should_rejectDocument_when_
    // extensionIsWebpButSignatureDoesNotMatch)는 RIFF 프리픽스 자체가 달라 startsWith에서 걸러지므로
    // matchesAt(오프셋 8, WEBP_FORMAT_SIGNATURE) 검증은 이 케이스가 없으면 실질적으로 테스트되지
    // 않는다(리뷰로 추가).
    @Test
    void should_rejectDocument_when_riffContainerFormatTagIsNotWebp() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        byte[] fakeAvi = {
                0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x41, 0x56, 0x49, 0x20
        };
        MockMultipartFile file = new MockMultipartFile("file", "vaccination.webp", "image/webp", fakeAvi);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadPetDocument("member-1", "pet-1", "VACCINATION", file, null));

        assertEquals("JPEG, PNG, WEBP, PDF 파일만 업로드할 수 있습니다.", exception.getMessage());
        verify(fileStorage, never()).store(any(), any(), any());
    }

    // "RIFF"까지는 있지만 크기 필드(4~7바이트)에서 잘려 "WEBP" 태그(8~11바이트)에 도달하지
    // 못하는 파일 — matchesAt의 길이 체크(bytes.length < offset + signature.length)가 이
    // 경계에서도 예외 없이 false로 안전하게 거부하는지 검증한다(리뷰로 추가).
    @Test
    void should_rejectDocument_when_webpFileIsTruncatedBeforeFormatTag() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        byte[] truncated = {0x52, 0x49, 0x46, 0x46, 0x00, 0x00};
        MockMultipartFile file = new MockMultipartFile("file", "vaccination.webp", "image/webp", truncated);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadPetDocument("member-1", "pet-1", "VACCINATION", file, null));

        assertEquals("JPEG, PNG, WEBP, PDF 파일만 업로드할 수 있습니다.", exception.getMessage());
        verify(fileStorage, never()).store(any(), any(), any());
    }

    // 영수증만 올리고 아직 청구서류(claim_document_url)는 없는 DRAFT 상태 클레임이 흔하다 —
    // 이 경우 arrangeDeletedFileCleanup(null)이 예외 없이 조용히 넘어가야 한다.
    @Test
    void should_skipCleanup_when_claimHasNoDocumentUrlYet() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        when(petDocumentMapper.findByPetId("pet-1")).thenReturn(List.of());
        Map<String, Object> draftClaim = new HashMap<>();
        draftClaim.put("claim_id", "claim-2");
        draftClaim.put("receipt_image_url", "receipts/claim-2.jpg");
        draftClaim.put("claim_document_url", null);
        when(insuranceMapper.findClaimsByPetId("pet-1")).thenReturn(List.of(draftClaim));
        when(petMapper.updateRegistrationNumber("pet-1", "member-1", null)).thenReturn(1);
        when(petMapper.deactivate("pet-1", "member-1")).thenReturn(1);

        service.deactivatePet("member-1", "pet-1");

        verify(fileStorage).delete("receipts/claim-2.jpg");
        verify(fileStorage, never()).delete(null);
        verify(insuranceMapper).deleteClaimsByPetId("pet-1");
    }

    @Test
    void should_throwNotFound_when_deletingMissingPet() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.deactivatePet("member-1", "pet-404"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verify(petMapper, never()).deactivate(anyString(), anyString());
    }

    @Test
    void should_throwNotFound_when_petIsAlreadyDeactivatedConcurrently() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        when(petDocumentMapper.findByPetId("pet-1")).thenReturn(List.of());
        when(petMapper.updateRegistrationNumber("pet-1", "member-1", null)).thenReturn(1);
        when(petMapper.deactivate("pet-1", "member-1")).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.deactivatePet("member-1", "pet-1"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    // #291: updateRegistrationNumber가 0행을 반환하면(예: 동시에 다른 요청이 먼저 처리)
    // deactivate까지 가지 않고 즉시 실패해야 한다.
    @Test
    void should_throwNotFound_when_regNumberClearFailsConcurrently() {
        PetServiceImpl service = service();
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        when(petDocumentMapper.findByPetId("pet-1")).thenReturn(List.of());
        when(petMapper.updateRegistrationNumber("pet-1", "member-1", null)).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.deactivatePet("member-1", "pet-1"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verify(petMapper, never()).deactivate(anyString(), anyString());
    }

    private static Map<String, Object> pet(String ownerId) {
        return map("pet_id", "pet-1", "member_id", ownerId, "name", "애월", "is_active", 1);
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
