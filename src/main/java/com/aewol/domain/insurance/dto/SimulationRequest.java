package com.aewol.domain.insurance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SimulationRequest {
    @NotBlank
    private String petId;
    @NotBlank
    private String breed;
    @NotNull
    private Integer age;
    private String medicalHistory;
}
