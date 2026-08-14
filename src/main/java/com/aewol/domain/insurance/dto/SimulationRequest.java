package com.aewol.domain.insurance.dto;

import com.aewol.domain.insurance.dto.validation.AllowedMedicalHistoryCode;
import java.util.List;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
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

    /**
     * 사용자가 조정한 연간 예상 의료비(원). null이면 서버 기본 상수를 사용한다.
     * 상한값은 S2(마리당 연 의료비 통계) 확정 전까지의 잠정 안전장치다.
     * TODO(S2): worker-2 리서치 결과의 상식적 배수로 재산정할 것.
     */
    @Min(0)
    @Max(50_000_000)
    private Long annualMedicalCostKrw;
}
