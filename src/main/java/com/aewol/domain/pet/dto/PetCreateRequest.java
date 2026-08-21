package com.aewol.domain.pet.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
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
    @Pattern(regexp = "^$|^(\\d{12}|\\d{15})$", message = "동물등록번호는 12자리 또는 15자리 숫자여야 합니다.")
    private String regNumber;
    private String registrationOwnerName;
    private String iconType;
    private String medicalHistory;
    private String profileImg;
}
