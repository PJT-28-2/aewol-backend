package com.aewol.domain.pet.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PetResponse {
    private String petId;
    private String memberId;
    private String name;
    private String species;
    private String breed;
    private String birthDate;
    private String gender;
    private Double weight;
    private String neutered;
    private String regNumber;
    private String iconType;
    private String medicalHistory;
    private String profileImg;
    private Boolean isActive;
}
