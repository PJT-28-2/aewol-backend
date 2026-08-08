package com.aewol.domain.pet.service;

import com.aewol.domain.pet.dto.PetRegistrationResponse;
import com.aewol.domain.pet.dto.PetRegistrationVerifyRequest;

public interface PetRegistrationService {
    PetRegistrationResponse verify(String memberId, String petId, PetRegistrationVerifyRequest request);
}
