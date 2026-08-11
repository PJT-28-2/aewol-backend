package com.aewol.domain.pet.service;

import com.aewol.domain.pet.dto.PetCharacterResponse;
import org.springframework.web.multipart.MultipartFile;

public interface PetCharacterService {

    PetCharacterResponse generate(String memberId, String petId, MultipartFile photo);
}
