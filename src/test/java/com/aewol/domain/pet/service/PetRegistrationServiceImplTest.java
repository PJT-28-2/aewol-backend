package com.aewol.domain.pet.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.pet.dto.PetRegistrationResponse;
import com.aewol.domain.pet.dto.PetRegistrationVerifyRequest;
import com.aewol.domain.pet.mapper.PetDocumentMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.pet.mapper.PetRegistrationMapper;
import com.aewol.external.apms.ApmsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PetRegistrationServiceImplTest {
    @Mock PetMapper petMapper;
    @Mock PetDocumentMapper petDocumentMapper;
    @Mock PetRegistrationMapper petRegistrationMapper;
    @Mock ApmsClient apmsClient;

    private PetRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PetRegistrationServiceImpl(
                petMapper, petDocumentMapper, petRegistrationMapper, apmsClient);
    }

    @Test
    void should_saveRegistration_when_ownerInformationMatches() {
        PetRegistrationVerifyRequest request = request("410000012345678", "홍길동", "1990.01.01");
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        when(apmsClient.verifyRegistration("410000012345678", "홍길동", "19900101"))
                .thenReturn(registration());
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("pet-1", "REGISTRATION")).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<Map<String, Object>>getArgument(0).put("docId", 31L);
            return null;
        }).when(petDocumentMapper).insert(any());
        when(petMapper.updateRegistrationNumber("pet-1", "member-1", "410000012345678")).thenReturn(1);
        // verify()는 저장 직후 이 값을 다시 조회해서 응답을 만든다(메모리 맵엔 last_synced_at이 없어서).
        when(petRegistrationMapper.findByPetIdAndDocId("pet-1", "31")).thenReturn(map(
                "doc_id", 31L, "pet_id", "pet-1", "reg_number", "410000012345678",
                "name", "몽이", "breed", "말티즈", "gender", "MALE", "neutered", "Y",
                "birth_date", "20220101",
                "reg_tm", LocalDateTime.of(2022, 8, 1, 12, 0, 0),
                "last_synced_at", LocalDateTime.of(2026, 8, 14, 11, 0, 0)));

        PetRegistrationResponse response = service.verify("member-1", "pet-1", request);

        assertTrue(response.isVerified());
        assertEquals("31", response.getDocId());
        assertEquals("몽이", response.getName());
        assertEquals("MALE", response.getGender());
        // 재동기화 직후에도 "마지막 동기화" 시각이 빠지지 않아야 한다(회귀 방지).
        assertEquals("2026-08-14T11:00:00", response.getLastSyncedAt());
        assertEquals("2022-08-01T12:00:00", response.getRegTm());
        verify(petRegistrationMapper).insert(argThat(row ->
                "410000012345678".equals(row.get("regNumber")) && Long.valueOf(31L).equals(row.get("docId"))));
    }

    @Test
    void should_normalizeNeuteredToY_when_apmsReturnsKoreanNeuteredValue() {
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        Map<String, Object> neuteredRegistration = registration();
        neuteredRegistration.put("neuterYn", "중성");
        when(apmsClient.verifyRegistration("410000012345678", "홍길동", null)).thenReturn(neuteredRegistration);
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("pet-1", "REGISTRATION")).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<Map<String, Object>>getArgument(0).put("docId", 31L);
            return null;
        }).when(petDocumentMapper).insert(any());
        when(petMapper.updateRegistrationNumber("pet-1", "member-1", "410000012345678")).thenReturn(1);
        when(petRegistrationMapper.findByPetIdAndDocId("pet-1", "31")).thenReturn(map(
                "doc_id", 31L, "pet_id", "pet-1", "reg_number", "410000012345678", "name", "몽이",
                "last_synced_at", LocalDateTime.of(2026, 8, 14, 11, 0, 0)));

        service.verify("member-1", "pet-1", request("410000012345678", "홍길동", null));

        verify(petRegistrationMapper).insert(argThat(row -> "Y".equals(row.get("neutered"))));
    }

    @Test
    void should_normalizeNeuteredToN_when_apmsReturnsKoreanNotNeuteredValue() {
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        Map<String, Object> notNeuteredRegistration = registration();
        notNeuteredRegistration.put("neuterYn", "미중성");
        when(apmsClient.verifyRegistration("410000012345678", "홍길동", null)).thenReturn(notNeuteredRegistration);
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("pet-1", "REGISTRATION")).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<Map<String, Object>>getArgument(0).put("docId", 31L);
            return null;
        }).when(petDocumentMapper).insert(any());
        when(petMapper.updateRegistrationNumber("pet-1", "member-1", "410000012345678")).thenReturn(1);
        when(petRegistrationMapper.findByPetIdAndDocId("pet-1", "31")).thenReturn(map(
                "doc_id", 31L, "pet_id", "pet-1", "reg_number", "410000012345678", "name", "몽이",
                "last_synced_at", LocalDateTime.of(2026, 8, 14, 11, 0, 0)));

        service.verify("member-1", "pet-1", request("410000012345678", "홍길동", null));

        verify(petRegistrationMapper).insert(argThat(row -> "N".equals(row.get("neutered"))));
    }

    @Test
    void should_dropNeutered_when_apmsReturnsUnrecognizedSingleChar() {
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        Map<String, Object> unknownCodeRegistration = registration();
        unknownCodeRegistration.put("neuterYn", "Z");
        when(apmsClient.verifyRegistration("410000012345678", "홍길동", null)).thenReturn(unknownCodeRegistration);
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("pet-1", "REGISTRATION")).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<Map<String, Object>>getArgument(0).put("docId", 31L);
            return null;
        }).when(petDocumentMapper).insert(any());
        when(petMapper.updateRegistrationNumber("pet-1", "member-1", "410000012345678")).thenReturn(1);
        when(petRegistrationMapper.findByPetIdAndDocId("pet-1", "31")).thenReturn(map(
                "doc_id", 31L, "pet_id", "pet-1", "reg_number", "410000012345678", "name", "몽이",
                "last_synced_at", LocalDateTime.of(2026, 8, 14, 11, 0, 0)));

        service.verify("member-1", "pet-1", request("410000012345678", "홍길동", null));

        // 1글자라 truncation은 안 나더라도, 프론트가 "Y" 아닌 값을 전부 "중성화 안함"으로
        // 표시하므로 인식 못한 값은 길이와 무관하게 저장하지 않고 null로 둔다.
        verify(petRegistrationMapper).insert(argThat(row -> row.get("neutered") == null));
    }

    @Test
    void should_dropNeutered_when_apmsReturnsUnrecognizedMultiCharValue() {
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        Map<String, Object> unknownValueRegistration = registration();
        unknownValueRegistration.put("neuterYn", "미상");
        when(apmsClient.verifyRegistration("410000012345678", "홍길동", null)).thenReturn(unknownValueRegistration);
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("pet-1", "REGISTRATION")).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<Map<String, Object>>getArgument(0).put("docId", 31L);
            return null;
        }).when(petDocumentMapper).insert(any());
        when(petMapper.updateRegistrationNumber("pet-1", "member-1", "410000012345678")).thenReturn(1);
        when(petRegistrationMapper.findByPetIdAndDocId("pet-1", "31")).thenReturn(map(
                "doc_id", 31L, "pet_id", "pet-1", "reg_number", "410000012345678", "name", "몽이",
                "last_synced_at", LocalDateTime.of(2026, 8, 14, 11, 0, 0)));

        service.verify("member-1", "pet-1", request("410000012345678", "홍길동", null));

        // 2글자 이상인데 "중성"을 포함하지 않는 값은 CHAR(1) truncation을 막기 위해 저장하지 않고 null로 둔다.
        verify(petRegistrationMapper).insert(argThat(row -> row.get("neutered") == null));
    }

    @Test
    void should_updateRegistration_when_petWasAlreadyVerified() {
        PetRegistrationVerifyRequest request = request("410000012345678", "홍길동", null);
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        when(apmsClient.verifyRegistration("410000012345678", "홍길동", null)).thenReturn(registration());
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("pet-1", "REGISTRATION"))
                .thenReturn(map("doc_id", 31L));
        when(petRegistrationMapper.findByRegNumber("410000012345678"))
                .thenReturn(map("pet_id", "pet-1"));
        when(petRegistrationMapper.update(any())).thenReturn(1);
        when(petMapper.updateRegistrationNumber("pet-1", "member-1", "410000012345678")).thenReturn(1);
        when(petRegistrationMapper.findByPetIdAndDocId("pet-1", "31")).thenReturn(map(
                "doc_id", 31L, "pet_id", "pet-1", "reg_number", "410000012345678", "name", "몽이",
                "last_synced_at", LocalDateTime.of(2026, 8, 14, 11, 0, 0)));

        PetRegistrationResponse response = service.verify("member-1", "pet-1", request);

        verify(petRegistrationMapper).update(argThat(row -> Long.valueOf(31L).equals(row.get("docId"))));
        verify(petRegistrationMapper, never()).insert(any());
        assertEquals("2026-08-14T11:00:00", response.getLastSyncedAt());
    }

    @Test
    void should_throwForbidden_when_nonOwnerVerifies() {
        when(petMapper.findById("pet-1")).thenReturn(pet("owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.verify("member-2", "pet-1", request("410000012345678", "홍길동", null)));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verifyNoInteractions(apmsClient, petDocumentMapper, petRegistrationMapper);
    }

    @Test
    void should_throwNotFound_when_petDoesNotExist() {
        when(petMapper.findById("pet-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.verify("member-1", "pet-404", request("410000012345678", "홍길동", null)));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verifyNoInteractions(apmsClient, petDocumentMapper, petRegistrationMapper);
    }

    @Test
    void should_throwBadRequest_when_ownerInformationIsMissing() {
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.verify("member-1", "pet-1", request("410000012345678", null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verifyNoInteractions(apmsClient);
    }

    @Test
    void should_throwBadRequest_when_registrationDoesNotMatchPet() {
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        Map<String, Object> differentPet = registration();
        differentPet.put("dogNm", "다른동물");
        when(apmsClient.verifyRegistration("410000012345678", "홍길동", null)).thenReturn(differentPet);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.verify("member-1", "pet-1", request("410000012345678", "홍길동", null)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verifyNoInteractions(petDocumentMapper, petRegistrationMapper);
    }

    @Test
    void should_throwConflict_when_registrationNumberBelongsToAnotherPet() {
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        when(apmsClient.verifyRegistration("410000012345678", "홍길동", null)).thenReturn(registration());
        when(petRegistrationMapper.findByRegNumber("410000012345678"))
                .thenReturn(map("pet_id", "pet-2"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.verify("member-1", "pet-1", request("410000012345678", "홍길동", null)));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verifyNoInteractions(petDocumentMapper);
    }

    @Test
    void should_returnRegistrationDetail_when_ownerRequestsIt() {
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        // reg_tm은 초가 0(경계값), last_synced_at은 초가 0이 아닌 값을 섞어서, LocalDateTime#toString()의
        // "초·나노초가 0이면 생략" 들쭉날쭉 포맷팅이 재발하지 않는지(DateTimeUtil.toIsoString이 항상
        // 초까지 포함하는지)를 함께 검증한다.
        when(petRegistrationMapper.findByPetIdAndDocId("pet-1", "doc-1")).thenReturn(map(
                "doc_id", "doc-1", "pet_id", "pet-1", "reg_number", "410000012345678",
                "name", "몽이", "breed", "말티즈", "gender", "MALE", "neutered", "Y",
                "birth_date", "20220101", "rfid_cd", "410000012345678", "rfid_gubun", "Y",
                "org_nm", "제주시", "office_tel", "064-123-4567", "apr_gbn_nm", "승인완료",
                "reg_tm", LocalDateTime.of(2023, 6, 2, 9, 15, 0),
                "apr_tm", LocalDateTime.of(2023, 6, 2, 10, 40, 0),
                "last_synced_at", LocalDateTime.of(2026, 8, 14, 10, 0, 37)));

        PetRegistrationResponse response = service.getDetail("member-1", "pet-1", "doc-1");

        assertEquals("doc-1", response.getDocId());
        assertEquals("410000012345678", response.getRegNumber());
        assertEquals("몽이", response.getName());
        assertEquals("2023-06-02T09:15:00", response.getRegTm());
        assertEquals("2023-06-02T10:40:00", response.getAprTm());
        assertEquals("2026-08-14T10:00:37", response.getLastSyncedAt());
        assertTrue(response.isVerified());
    }

    @Test
    void should_throwNotFound_when_registrationDetailMissing() {
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        when(petRegistrationMapper.findByPetIdAndDocId("pet-1", "doc-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getDetail("member-1", "pet-1", "doc-404"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void should_throwForbidden_when_nonOwnerRequestsRegistrationDetail() {
        when(petMapper.findById("pet-1")).thenReturn(pet("owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getDetail("member-2", "pet-1", "doc-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verifyNoInteractions(petRegistrationMapper);
    }

    @Test
    void should_throwNotFound_when_petDoesNotExist_onGetDetail() {
        when(petMapper.findById("pet-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getDetail("member-1", "pet-404", "doc-1"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verifyNoInteractions(petRegistrationMapper);
    }

    @Test
    void should_deleteRegistrationAndClearRegNumber_when_ownerCancels() {
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        when(petRegistrationMapper.deleteByPetIdAndDocId("pet-1", "doc-1")).thenReturn(1);

        service.cancel("member-1", "pet-1", "doc-1");

        verify(petRegistrationMapper).deleteByPetIdAndDocId("pet-1", "doc-1");
        verify(petMapper).updateRegistrationNumber("pet-1", "member-1", null);
    }

    @Test
    void should_throwNotFound_when_registrationAlreadyGone_onCancel() {
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        when(petRegistrationMapper.deleteByPetIdAndDocId("pet-1", "doc-1")).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.cancel("member-1", "pet-1", "doc-1"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verify(petMapper, never()).updateRegistrationNumber(anyString(), anyString(), any());
    }

    @Test
    void should_throwForbidden_when_nonOwnerCancels() {
        when(petMapper.findById("pet-1")).thenReturn(pet("owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.cancel("member-2", "pet-1", "doc-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verifyNoInteractions(petRegistrationMapper);
    }

    @Test
    void should_throwNotFound_when_petDoesNotExist_onCancel() {
        when(petMapper.findById("pet-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.cancel("member-1", "pet-404", "doc-1"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verifyNoInteractions(petRegistrationMapper);
    }

    private PetRegistrationVerifyRequest request(String regNumber, String userName, String birthDate) {
        return new PetRegistrationVerifyRequest(regNumber, userName, birthDate);
    }

    private Map<String, Object> pet(String memberId) {
        return map("pet_id", "pet-1", "member_id", memberId, "name", "몽이",
                "birth_date", "2022-01-01", "gender", "MALE", "is_active", 1);
    }

    private Map<String, Object> registration() {
        return map("dogRegNo", "410000012345678", "dogNm", "몽이", "kindNm", "말티즈",
                "sexNm", "수컷", "neuterYn", "Y", "birthDt", "20220101",
                "rfidCd", "410000012345678", "rfidGubun", "Y", "orgNm", "제주시",
                "officeTel", "064-123-4567", "aprGbnNm", "승인", "regTm", "20220801120000");
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
