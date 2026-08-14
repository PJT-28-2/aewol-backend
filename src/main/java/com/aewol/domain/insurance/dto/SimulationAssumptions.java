package com.aewol.domain.insurance.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SimulationAssumptions {
    // annualClaimCount(연 진료 횟수)는 출처가 확정되지 않아 제거했다.
    // TODO(S2): worker-2가 출처를 확정하면 Decision 5 분기에 맞춰 다시 노출한다.
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
