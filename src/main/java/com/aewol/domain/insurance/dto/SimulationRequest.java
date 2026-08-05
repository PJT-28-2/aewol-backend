package com.aewol.domain.insurance.dto;

import java.util.List;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SimulationRequest {
    @NotBlank
    private String petId;
    @NotNull
    private List<String> medicalHistoryCodes;
}
