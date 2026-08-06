package com.aewol.domain.insurance.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BreakEvenScenario {
    private int years;
    private long cumulativePremiumKrw;
    private long expectedReimbursementKrw;
    /** Boolean(래퍼)로 선언해야 Lombok이 getIsFavorable()을 생성해 JSON 필드명이 isFavorable로 나간다 */
    private Boolean isFavorable;
    private long differenceKrw;
}
