package com.aewol.domain.insurance.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductCoverageResponse {
    private String coverageName;
    private String coverageAmount;
    /** DENTAL / URINARY / FOREIGN_BODY / JOINT / SKIN / DIGESTIVE / OTHER / NONE */
    private String medicalCategory;
}
