package com.aewol.domain.insurance.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SimulationAssumptions {
    // annualClaimCount(연 진료 횟수)는 출처가 확정되지 않아 제거했다.
    //
    // 이슈 #178에서 확보 불가로 종결됐다 — 펫보험 지급건수·건당 지급보험금은 공개
    // 집계가 없고(보유계약건수·원수보험료까지만 공개), 동물병원 진료비 현황조사도
    // 항목별 단가만 제공한다. 따라서 이 필드는 노출하지 않는 것이 정확한 상태다.
    // 출처가 생기면 InsuranceSimulationServiceImpl의 CLAIM_COUNT_SOURCE_CONFIRMED와
    // 함께 되살릴 것.
    private long annualExpectedVetCostKrw;
    private String assumptionSource;
    /**
     * 사용자가 annualMedicalCostKrw를 조정해 재계산한 결과인지 여부.
     *
     * <p>Boolean(래퍼)로 선언해야 Lombok이 {@code getIsUserAdjusted()}를 생성해 JSON 필드명이
     * {@code isUserAdjusted}로 나간다. primitive {@code boolean}이면 getter가
     * {@code isUserAdjusted()}가 되어 Jackson이 {@code userAdjusted}로 직렬화하고,
     * {@code assumptions.isUserAdjusted}를 읽는 프론트에서 값이 영원히 undefined가 된다.
     * {@link BreakEvenScenario#getIsFavorable()}와 같은 이유다.</p>
     */
    private Boolean isUserAdjusted;
}
