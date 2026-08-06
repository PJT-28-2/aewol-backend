package com.aewol.domain.insurance.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SimulationResponse {
    private SimulationAssumptions assumptions;
    private String preExistingConditionWarning;
    private InsuranceAdvice insuranceAdvice;
    private List<RecommendedProductResponse> recommendedProducts;
}
