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
    private Integer reimbursementRatePct;
    /**
     * CONFIRMED_OWN_COVERAGE_NAME / ASSUMED_FROM_RESEARCH / REGULATORY_BOUND / UNVERIFIED
     *
     * <p>REGULATORY_BOUND는 확인값이 아니라 금감원 규제 상한(70%)이다(V34).</p>
     */
    private String reimbursementConfidence;
    /** 담보별 환급률이 갈리는 상품의 차이를 사람이 읽는 문장으로 기록한 노트(화면 표시용) */
    private String reimbursementRateNote;
    private String regulatoryCapWarning;
    /** 표시 전용. 시드 담보 한도(1,000만~4,000만원) 대비 연 의료비가 낮아 산식에는 쓰이지 않는다 */
    private BigDecimal annualLimitKrw;
    private List<ProductPlanTierResponse> planTiers;
    private List<ProductCoverageResponse> coverages;
}
