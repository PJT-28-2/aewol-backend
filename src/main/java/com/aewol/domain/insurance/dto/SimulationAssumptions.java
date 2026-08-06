package com.aewol.domain.insurance.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SimulationAssumptions {
    private int annualClaimCount;
    private long annualExpectedVetCostKrw;
    private String assumptionSource;
}
