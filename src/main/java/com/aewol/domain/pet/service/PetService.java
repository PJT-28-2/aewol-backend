package com.aewol.domain.pet.service;

import com.aewol.domain.pet.dto.PetCreateRequest;
import com.aewol.domain.pet.dto.PetResponse;
import java.util.List;

public interface PetService {
    PetResponse createPet(String memberId, PetCreateRequest request);
    List<PetResponse> getPetsByMember(String memberId);
    PetResponse getPet(String petId);
    void updatePet(String petId, PetCreateRequest request);
    void deactivatePet(String petId);
}
