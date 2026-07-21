package com.aewol.domain.pet.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.pet.dto.PetCreateRequest;
import com.aewol.domain.pet.dto.PetResponse;
import com.aewol.domain.pet.mapper.PetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PetServiceImpl implements PetService {

    private final PetMapper petMapper;

    @Override
    @Transactional
    public PetResponse createPet(String memberId, PetCreateRequest request) {
        String petId = UUID.randomUUID().toString();
        Map<String, Object> pet = new HashMap<>();
        pet.put("petId", petId);
        pet.put("memberId", memberId);
        pet.put("name", request.getName());
        pet.put("species", request.getSpecies());
        pet.put("breed", request.getBreed());
        pet.put("birthDate", request.getBirthDate());
        pet.put("gender", request.getGender());
        pet.put("weight", request.getWeight());
        pet.put("neutered", request.getNeutered());
        pet.put("regNumber", request.getRegNumber());
        pet.put("iconType", request.getIconType());
        pet.put("medicalHistory", request.getMedicalHistory());
        pet.put("profileImg", request.getProfileImg());
        petMapper.insert(pet);

        return getPet(petId);
    }

    @Override
    public List<PetResponse> getPetsByMember(String memberId) {
        return petMapper.findByMemberId(memberId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public PetResponse getPet(String petId) {
        Map<String, Object> pet = petMapper.findById(petId);
        if (pet == null) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
        return toResponse(pet);
    }

    @Override
    @Transactional
    public void updatePet(String petId, PetCreateRequest request) {
        if (petMapper.findById(petId) == null) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
        Map<String, Object> pet = new HashMap<>();
        pet.put("petId", petId);
        pet.put("name", request.getName());
        pet.put("species", request.getSpecies());
        pet.put("breed", request.getBreed());
        pet.put("birthDate", request.getBirthDate());
        pet.put("gender", request.getGender());
        pet.put("weight", request.getWeight());
        pet.put("neutered", request.getNeutered());
        pet.put("regNumber", request.getRegNumber());
        pet.put("iconType", request.getIconType());
        pet.put("medicalHistory", request.getMedicalHistory());
        pet.put("profileImg", request.getProfileImg());
        petMapper.update(pet);
    }

    @Override
    @Transactional
    public void deactivatePet(String petId) {
        if (petMapper.findById(petId) == null) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
        petMapper.deactivate(petId);
    }

    private PetResponse toResponse(Map<String, Object> pet) {
        return PetResponse.builder()
                .petId((String) pet.get("pet_id"))
                .memberId((String) pet.get("member_id"))
                .name((String) pet.get("name"))
                .species((String) pet.get("species"))
                .breed((String) pet.get("breed"))
                .birthDate(pet.get("birth_date") != null ? pet.get("birth_date").toString() : null)
                .gender((String) pet.get("gender"))
                .weight(pet.get("weight") != null ? ((Number) pet.get("weight")).doubleValue() : null)
                .neutered((String) pet.get("neutered"))
                .regNumber((String) pet.get("reg_number"))
                .iconType((String) pet.get("icon_type"))
                .medicalHistory((String) pet.get("medical_history"))
                .profileImg((String) pet.get("profile_img"))
                .isActive(pet.get("is_active") != null && ((Number) pet.get("is_active")).intValue() == 1)
                .build();
    }
}
