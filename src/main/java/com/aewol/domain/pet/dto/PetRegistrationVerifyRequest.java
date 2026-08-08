package com.aewol.domain.pet.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PetRegistrationVerifyRequest {
    @NotBlank
    @Pattern(regexp = "^(\\d{12}|\\d{15})$", message = "동물등록번호는 12자리 또는 15자리 숫자여야 합니다.")
    private String regNumber;
    private String userName;
    private String birthDate;
}
