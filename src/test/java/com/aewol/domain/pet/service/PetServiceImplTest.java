package com.aewol.domain.pet.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.pet.dto.PetCreateRequest;
import com.aewol.domain.pet.mapper.PetMapper;
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

    @Test
    void should_returnPet_when_memberOwnsPet() {
        PetServiceImpl service = new PetServiceImpl(petMapper);
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));

        assertEquals("pet-1", service.getPet("member-1", "pet-1").getPetId());
    }

    @Test
    void should_throwForbidden_when_memberCannotViewPet() {
        PetServiceImpl service = new PetServiceImpl(petMapper);
        when(petMapper.findById("pet-1")).thenReturn(pet("owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getPet("member-2", "pet-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    @Test
    void should_throwNotFound_when_petDoesNotExist() {
        PetServiceImpl service = new PetServiceImpl(petMapper);
        when(petMapper.findById("pet-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getPet("member-1", "pet-404"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void should_throwForbidden_when_nonOwnerUpdatesPet() {
        PetServiceImpl service = new PetServiceImpl(petMapper);
        when(petMapper.findById("pet-1")).thenReturn(pet("owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updatePet("member-2", "pet-1", null));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verify(petMapper, never()).update(any());
    }

    @Test
    void should_updatePet_when_memberOwnsPet() {
        PetServiceImpl service = new PetServiceImpl(petMapper);
        PetCreateRequest request = mock(PetCreateRequest.class);
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));

        service.updatePet("member-1", "pet-1", request);

        verify(petMapper).update(argThat(row -> "pet-1".equals(row.get("petId"))));
    }

    @Test
    void should_throwNotFound_when_updatingMissingPet() {
        PetServiceImpl service = new PetServiceImpl(petMapper);
        when(petMapper.findById("pet-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updatePet("member-1", "pet-404", mock(PetCreateRequest.class)));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verify(petMapper, never()).update(any());
    }

    @Test
    void should_throwForbidden_when_nonOwnerDeletesPet() {
        PetServiceImpl service = new PetServiceImpl(petMapper);
        when(petMapper.findById("pet-1")).thenReturn(pet("owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.deactivatePet("member-2", "pet-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verify(petMapper, never()).deactivate(anyString());
    }

    @Test
    void should_deactivatePet_when_memberOwnsPet() {
        PetServiceImpl service = new PetServiceImpl(petMapper);
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));

        service.deactivatePet("member-1", "pet-1");

        verify(petMapper).deactivate("pet-1");
    }

    @Test
    void should_throwNotFound_when_deletingMissingPet() {
        PetServiceImpl service = new PetServiceImpl(petMapper);
        when(petMapper.findById("pet-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.deactivatePet("member-1", "pet-404"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verify(petMapper, never()).deactivate(anyString());
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
