package com.aewol.domain.pet.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.storage.FileStorage;
import com.aewol.domain.pet.dto.PetCreateRequest;
import com.aewol.domain.pet.dto.PetResponse;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.pet.mapper.PetDocumentMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class PetServiceImplTest {

    @Mock PetMapper petMapper;
    @Mock PetDocumentMapper petDocumentMapper;    @Mock FileStorage fileStorage;
    @Mock PetRegistrationService petRegistrationService;

    private PetServiceImpl service() {
        return new PetServiceImpl(petMapper, petDocumentMapper, fileStorage, petRegistrationService);
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
        when(petMapper.deactivate("pet-1", "member-1")).thenReturn(1);

        service.deactivatePet("member-1", "pet-1");

        verify(petMapper).deactivate("pet-1", "member-1");
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
        when(petMapper.deactivate("pet-1", "member-1")).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.deactivatePet("member-1", "pet-1"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
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
