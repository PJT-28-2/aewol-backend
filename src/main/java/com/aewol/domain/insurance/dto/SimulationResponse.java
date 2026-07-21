package com.aewol.domain.insurance.dto;

import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;

@Getter
@Builder
public class SimulationResponse {
    private String simId;
    private String breed;
    private Integer age;
    private BigDecimal estimatedAnnualCost;
    private BigDecimal premium;
    private BigDecimal deductible;
    private Integer breakEvenYear;
    private String recommendation;
}
