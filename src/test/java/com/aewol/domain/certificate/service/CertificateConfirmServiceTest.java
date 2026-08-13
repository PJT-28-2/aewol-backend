package com.aewol.domain.certificate.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.certificate.dto.RegistrationConfirmRequest;
import com.aewol.domain.pet.dto.PetRegistrationResponse;
import com.aewol.domain.pet.mapper.PetDocumentMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.pet.mapper.PetRegistrationMapper;
import com.aewol.external.apms.ApmsClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificateConfirmServiceTest {

    @Mock ApmsClient apmsClient;
    @Mock PetMapper petMapper;
    @Mock PetDocumentMapper petDocumentMapper;
    @Mock PetRegistrationMapper petRegistrationMapper;

    private CertificateServiceImpl service() {
        return new CertificateServiceImpl(apmsClient, petMapper, petDocumentMapper, petRegistrationMapper);
    }

    private Map<String, Object> pet(String memberId) {
        Map<String, Object> pet = new HashMap<>();
        pet.put("pet_id", "9001");
        pet.put("member_id", memberId);
        return pet;
    }

    private RegistrationConfirmRequest request(String petId, String regNumber) {
        RegistrationConfirmRequest.Candidate candidate = new RegistrationConfirmRequest.Candidate();
        candidate.setPetId(petId);
        candidate.setRegNumber(regNumber);
        candidate.setName("보리");
        candidate.setBreed("말티즈");
        candidate.setRegTm("20240315");
        RegistrationConfirmRequest request = new RegistrationConfirmRequest();
        request.setCandidates(List.of(candidate));
        return request;
    }

    @Test
    @DisplayName("처음 연동하면 등록증 문서와 동물등록정보를 새로 만든다")
    void should_insert_when_firstLink() {
        when(petMapper.findById("9001")).thenReturn(pet("m1"));
        when(petMapper.updateRegistrationNumber(anyString(), anyString(), anyString())).thenReturn(1);
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("9001", "REGISTRATION")).thenReturn(null);

        List<PetRegistrationResponse> saved = service().confirmRegistration("m1", request("9001", "R-1"));

        assertEquals(1, saved.size());
        assertEquals("R-1", saved.get(0).getRegNumber());
        verify(petDocumentMapper).insert(anyMap());
        verify(petRegistrationMapper).insert(anyMap());
        verify(petRegistrationMapper, never()).update(anyMap());
    }

    @Test
    @DisplayName("이미 연동된 반려동물이면 기존 문서를 덮어쓴다")
    void should_update_when_alreadyLinked() {
        Map<String, Object> document = new HashMap<>();
        document.put("doc_id", "77");
        when(petMapper.findById("9001")).thenReturn(pet("m1"));
        when(petMapper.updateRegistrationNumber(anyString(), anyString(), anyString())).thenReturn(1);
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("9001", "REGISTRATION")).thenReturn(document);
        when(petRegistrationMapper.update(anyMap())).thenReturn(1);

        service().confirmRegistration("m1", request("9001", "R-1"));

        verify(petDocumentMapper, never()).insert(anyMap());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(petRegistrationMapper).update(captor.capture());
        assertEquals("77", String.valueOf(captor.getValue().get("docId")));
    }

    // 후보 값은 우리 sync 응답에서 나왔지만 클라이언트를 거쳐 돌아온다. 남의 반려동물에
    // 붙이려는 요청을 서버에서 막아야 한다.
    @Test
    @DisplayName("남의 반려동물에는 연동할 수 없다")
    void should_rejectForbidden_when_petBelongsToAnotherMember() {
        when(petMapper.findById("9001")).thenReturn(pet("owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().confirmRegistration("m2", request("9001", "R-1")));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verify(petRegistrationMapper, never()).insert(anyMap());
    }

    @Test
    @DisplayName("다른 반려동물이 쓰는 등록번호는 연동할 수 없다")
    void should_rejectConflict_when_regNumberUsedByAnotherPet() {
        Map<String, Object> other = new HashMap<>();
        other.put("pet_id", "9002");
        when(petMapper.findById("9001")).thenReturn(pet("m1"));
        when(petRegistrationMapper.findByRegNumber("R-1")).thenReturn(other);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().confirmRegistration("m1", request("9001", "R-1")));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(petRegistrationMapper, never()).insert(anyMap());
    }

    @Test
    @DisplayName("없는 반려동물이면 404")
    void should_rejectNotFound_when_petMissing() {
        when(petMapper.findById("9999")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().confirmRegistration("m1", request("9999", "R-1")));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    // 연동 확정은 APMS를 다시 부르지 않는다. 사용자가 방금 조회해 고른 값을 쓰고,
    // 여기서 재조회하면 소유자 이름을 한 번 더 받아야 해 화면 흐름이 끊긴다.
    @Test
    @DisplayName("확정 단계에서는 외부 조회를 다시 하지 않는다")
    void should_notCallApms_when_confirming() {
        when(petMapper.findById("9001")).thenReturn(pet("m1"));
        when(petMapper.updateRegistrationNumber(anyString(), anyString(), anyString())).thenReturn(1);
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("9001", "REGISTRATION")).thenReturn(null);

        service().confirmRegistration("m1", request("9001", "R-1"));

        verify(apmsClient, never()).findRegistration(anyString(), any());
        verify(apmsClient, never()).verifyRegistration(anyString(), any(), any());
    }
}
