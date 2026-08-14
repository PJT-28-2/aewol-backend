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
    /** 담보별 환급률이 갈리는 상품의 차이를 사람이 읽는 문장으로 기록한 노트(화면 표시용) */
    private String reimbursementRateNote;
    private String regulatoryCapWarning;
    private BigDecimal deductibleKrw;
    /** PER_VISIT / PER_YEAR */
    private String deductibleBasis;
    /**
     * 자기부담금이 실제로 손익분기 계산에 반영됐는지.
     *
     * <p>deductibleKrw가 채워져 있어도 기준(건당/연간)이 미확인이면 반영하지 않으므로,
     * 화면은 금액 유무가 아니라 이 플래그로 "자기부담금 미반영" 고지를 판단해야 한다.</p>
     * <p>필드명을 {@code isDeductibleApplied}로 짓지 말 것 — Lombok getter가
     * {@code isDeductibleApplied()}가 되어 Jackson이 {@code deductibleApplied}로
     * 직렬화하면서 프론트가 읽는 이름과 어긋난다(SimulationAssumptions에서 실제로 겪은 버그).</p>
     */
    private Boolean deductibleApplied;
    /** 표시 전용. 시드 담보 한도 대비 연 의료비가 낮아 산식에는 쓰이지 않는다 */
    private BigDecimal annualLimitKrw;
    private String reimbursementSourceUrl;
    private boolean breakEvenAvailable;
    private List<BreakEvenScenario> breakEvenScenarios;
}
