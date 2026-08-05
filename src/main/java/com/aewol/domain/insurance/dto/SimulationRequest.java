package com.aewol.domain.insurance.dto;

import com.aewol.domain.insurance.dto.validation.AllowedMedicalHistoryCode;
import java.util.List;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SimulationRequest {
    @NotBlank
    private String petId;

    @NotEmpty
    private List<@NotBlank @AllowedMedicalHistoryCode String> medicalHistoryCodes;
}
