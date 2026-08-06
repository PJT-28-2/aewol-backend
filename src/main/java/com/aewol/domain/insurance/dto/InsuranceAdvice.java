package com.aewol.domain.insurance.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class InsuranceAdvice {
    /** FAVORABLE / NEUTRAL / UNFAVORABLE */
    private String verdict;
    private String message;
}
