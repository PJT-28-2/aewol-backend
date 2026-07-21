package com.aewol.domain.pet.dto;

import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PetCreateRequest {
    @NotBlank
    private String name;
    @NotBlank
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
}
