package com.aewol.domain.certificate.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aewol.domain.certificate.dto.ApmsSyncRequest;
import com.aewol.domain.certificate.dto.PetRegistrationCandidateResponse;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.external.apms.ApmsClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CertificateServiceImplTest {

    @Mock ApmsClient apmsClient;
    @Mock PetMapper petMapper;

    private CertificateServiceImpl service() {
        return new CertificateServiceImpl(apmsClient, petMapper);
    }

    private ApmsSyncRequest request() {
        ApmsSyncRequest request = mock(ApmsSyncRequest.class);
        when(request.getRegNumber()).thenReturn("410000019876543");
        when(request.getUserName()).thenReturn("김애월");
        return request;
    }

    private Map<String, Object> apmsItem() {
        Map<String, Object> item = new HashMap<>();
        item.put("dogRegNo", "410000019876543");
        item.put("dogNm", "소로");
        item.put("kindNm", "포메라니안");
        item.put("sexNm", "암컷");
        item.put("neuterYn", "중성");
        item.put("birthDt", "2023-05-12");
        item.put("rfidCd", "410000019876543");
        item.put("rfidGubun", "Y");
        item.put("orgNm", "제주특별자치도 제주시청");
        item.put("aprGbNm", "승인");
        item.put("regTm", "2023-06-02");
        item.put("aprTm", "2023-06-02");
        return item;
    }

    @Test
    void should_returnEmptyList_when_apmsHasNoMatch() {
        when(apmsClient.findRegistration("410000019876543", "김애월"))
                .thenReturn(Optional.empty());

        List<PetRegistrationCandidateResponse> result = service().syncRegistration("member-1", request());

        assertTrue(result.isEmpty());
    }

    @Test
    void should_fillPetId_when_memberOwnsPetWithMatchingRegNumber() {
        when(apmsClient.findRegistration("410000019876543", "김애월"))
                .thenReturn(Optional.of(apmsItem()));
        Map<String, Object> pet = new HashMap<>();
        pet.put("pet_id", "pet-1");
        pet.put("reg_number", "410000019876543");
        when(petMapper.findByMemberId("member-1")).thenReturn(List.of(pet));

        List<PetRegistrationCandidateResponse> result = service().syncRegistration("member-1", request());

        assertEquals(1, result.size());
        PetRegistrationCandidateResponse candidate = result.get(0);
        assertEquals("pet-1", candidate.getPetId());
        assertEquals("소로", candidate.getName());
        assertEquals("포메라니안", candidate.getBreed());
        assertEquals("승인", candidate.getAprGbnNm());
    }

    @Test
    void should_returnNullPetId_when_noOwnedPetMatchesRegNumber() {
        when(apmsClient.findRegistration("410000019876543", "김애월"))
                .thenReturn(Optional.of(apmsItem()));
        Map<String, Object> otherPet = new HashMap<>();
        otherPet.put("pet_id", "pet-2");
        otherPet.put("reg_number", "999999999999999");
        when(petMapper.findByMemberId("member-1")).thenReturn(List.of(otherPet));

        List<PetRegistrationCandidateResponse> result = service().syncRegistration("member-1", request());

        assertNull(result.get(0).getPetId());
    }
}
