package com.aewol.domain.insurance.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RecommendedProductResponse {
    private String productId;
    private String companyName;
    private String productName;
    private BigDecimal monthlyPremiumKrw;
    /** FIXED / BUNDLED_TIER / INDEPENDENT_SELECTABLE / UNKNOWN */
    private String reimbursementStructure;
    private Integer reimbursementRatePct;
    /** CONFIRMED_OWN_COVERAGE_NAME / ASSUMED_FROM_RESEARCH / UNVERIFIED */
    private String reimbursementConfidence;
    private String regulatoryCapWarning;
    private boolean breakEvenAvailable;
    private List<BreakEvenScenario> breakEvenScenarios;
}
