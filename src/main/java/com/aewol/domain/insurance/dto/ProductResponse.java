package com.aewol.domain.insurance.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductResponse {
    private String productId;
    private String companyName;
    private String productName;
    private BigDecimal monthlyPremiumKrw;
    private Integer minAge;
    private Integer maxAge;
    /** CONFIRMED / ASSUMED / UNKNOWN_DEFAULT */
    private String ageSubjectConfidence;
    private String subscriptionType;
    private String productUrl;
    private String phone;
    /** FIXED / BUNDLED_TIER / INDEPENDENT_SELECTABLE / UNKNOWN */
    private String reimbursementStructure;
    /** CONFIRMED_OWN_COVERAGE_NAME / ASSUMED_FROM_RESEARCH / UNVERIFIED */
    private String reimbursementConfidence;
    private String regulatoryCapWarning;
    private List<ProductPlanTierResponse> planTiers;
    private List<ProductCoverageResponse> coverages;
}
