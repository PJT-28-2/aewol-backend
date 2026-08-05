package com.aewol.domain.insurance.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductPlanTierResponse {
    private String tierName;
    private Integer reimbursementRatePct;
    /** Boolean(래퍼)로 선언해야 Lombok이 getIsReferenceTier()를 생성해 JSON 필드명이 isReferenceTier로 나간다 */
    private Boolean isReferenceTier;
    private BigDecimal premiumKrw;
}
