package com.aewol.domain.insurance.service;

import com.aewol.domain.insurance.dto.ProductCoverageResponse;
import com.aewol.domain.insurance.dto.ProductPlanTierResponse;
import com.aewol.domain.insurance.dto.ProductResponse;
import com.aewol.domain.insurance.mapper.InsuranceMapper;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InsuranceProductServiceImpl implements InsuranceProductService {

    private final InsuranceMapper insuranceMapper;

    @Override
    public List<ProductResponse> getProducts(String petType, Integer age, String sort) {
        List<Map<String, Object>> products = insuranceMapper.findProductsBySpecies(petType);
        if (products.isEmpty()) {
            return List.of();
        }

        List<Long> productIds = products.stream()
                .map(product -> ((Number) product.get("product_id")).longValue())
                .collect(Collectors.toList());
        Map<Long, List<Map<String, Object>>> coveragesByProduct = insuranceMapper.findCoveragesByProductIds(productIds)
                .stream()
                .collect(Collectors.groupingBy(coverage -> ((Number) coverage.get("product_id")).longValue()));
        Map<Long, List<Map<String, Object>>> tiersByProduct = insuranceMapper.findPlanTiersByProductIds(productIds)
                .stream()
                .collect(Collectors.groupingBy(tier -> ((Number) tier.get("product_id")).longValue()));

        Comparator<Map<String, Object>> premiumComparator = Comparator.comparing(
                product -> (BigDecimal) product.get("premium_monthly_equiv"));
        if ("premium_desc".equals(sort)) {
            premiumComparator = premiumComparator.reversed();
        }

        Comparator<Map<String, Object>> finalComparator = (age == null)
                ? premiumComparator
                : Comparator.<Map<String, Object>, Integer>comparing(
                                product -> InsuranceProductPolicy.isEligibleByAge(product, age) ? 0 : 1)
                        .thenComparing(premiumComparator);

        return products.stream()
                .filter(product -> age == null || !isConfirmedAgeMismatch(product, age))
                .sorted(finalComparator)
                .map(product -> toProductResponse(product, coveragesByProduct, tiersByProduct))
                .collect(Collectors.toList());
    }

    /**
     * CONFIRMED로 표기된 나이 기준 상품만 나이 범위 필터를 강하게 적용한다 (그 외는 정렬 순위만 낮춘다).
     *
     * <p>{@code age_basis='OWNER'}인 상품(메리츠, 현대해상 스탠다드 등)은
     * {@link InsuranceProductPolicy#isEligibleByAge}가 항상 적격으로 판정하므로,
     * {@code age_subject_confidence='CONFIRMED'}가 백필돼도 이 상품들이 카탈로그에서
     * 하드 배제되지 않는다 (계획서 Critic C-1 회귀 방지).</p>
     */
    private boolean isConfirmedAgeMismatch(Map<String, Object> product, int age) {
        if (!"CONFIRMED".equals(product.get("age_subject_confidence"))) {
            return false;
        }
        return !InsuranceProductPolicy.isEligibleByAge(product, age);
    }

    private ProductResponse toProductResponse(Map<String, Object> product,
            Map<Long, List<Map<String, Object>>> coveragesByProduct,
            Map<Long, List<Map<String, Object>>> tiersByProduct) {
        Long productId = ((Number) product.get("product_id")).longValue();

        List<ProductCoverageResponse> coverages = coveragesByProduct.getOrDefault(productId, List.of()).stream()
                .map(coverage -> ProductCoverageResponse.builder()
                        .coverageName((String) coverage.get("coverage_name"))
                        .coverageAmount((String) coverage.get("coverage_amount"))
                        .medicalCategory((String) coverage.get("medical_category"))
                        .build())
                .collect(Collectors.toList());

        List<Map<String, Object>> tiers = tiersByProduct.getOrDefault(productId, List.of());
        List<ProductPlanTierResponse> planTiers = tiers.stream()
                .map(tier -> ProductPlanTierResponse.builder()
                        .tierName((String) tier.get("tier_name"))
                        .reimbursementRatePct((Integer) tier.get("reimbursement_rate_pct"))
                        .isReferenceTier(toBool(tier.get("is_reference_tier")))
                        .premiumKrw((BigDecimal) tier.get("premium_krw"))
                        .build())
                .collect(Collectors.toList());

        // 환급률/신뢰도/연간한도의 진실 원천은 insurance_product가 아니라 견적 기준
        // 티어(is_reference_tier=1)다 (V28 재설계). 상품에 티어가 없으면(아직 리서치
        // 미완료) 전부 null(=미확인)로 남긴다.
        Map<String, Object> referenceTier = tiers.stream()
                .filter(tier -> Boolean.TRUE.equals(toBool(tier.get("is_reference_tier"))))
                .findFirst()
                .orElse(null);

        return ProductResponse.builder()
                .productId(String.valueOf(productId))
                .companyName((String) product.get("company_name"))
                .productName((String) product.get("product_name"))
                .monthlyPremiumKrw((BigDecimal) product.get("premium_monthly_equiv"))
                .minAge((Integer) product.get("join_age_min"))
                .maxAge((Integer) product.get("join_age_max"))
                .ageSubjectConfidence((String) product.get("age_subject_confidence"))
                .subscriptionType((String) product.get("join_type"))
                .productUrl((String) product.get("product_url"))
                .phone((String) product.get("phone"))
                .reimbursementStructure((String) product.get("reimbursement_structure"))
                .reimbursementRatePct(referenceTier == null ? null : (Integer) referenceTier.get("reimbursement_rate_pct"))
                .reimbursementConfidence(referenceTier == null ? null : (String) referenceTier.get("reimbursement_confidence"))
                .reimbursementRateNote((String) product.get("reimbursement_rate_note"))
                .regulatoryCapWarning((String) product.get("regulatory_cap_warning"))
                .annualLimitKrw(referenceTier == null ? null : (BigDecimal) referenceTier.get("annual_limit_krw"))
                .planTiers(planTiers)
                .coverages(coverages)
                .build();
    }

    /** TINYINT(1)은 커넥터 설정에 따라 Boolean 또는 Number로 반환된다 */
    private static Boolean toBool(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() == 1;
        }
        return null;
    }
}
