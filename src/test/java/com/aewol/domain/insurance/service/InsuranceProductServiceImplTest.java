package com.aewol.domain.insurance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.aewol.domain.insurance.dto.ProductResponse;
import com.aewol.domain.insurance.mapper.InsuranceMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InsuranceProductServiceImplTest {

    @Mock InsuranceMapper insuranceMapper;

    private InsuranceProductServiceImpl service;

    private Map<String, Object> product(long productId, BigDecimal monthlyPremium, Integer joinAgeMin,
            Integer joinAgeMax, String ageSubjectConfidence) {
        Map<String, Object> product = new HashMap<>();
        product.put("product_id", productId);
        product.put("company_name", "KB손보");
        product.put("product_name", "KB 다이렉트 금쪽같은 펫보험");
        product.put("premium_monthly_equiv", monthlyPremium);
        product.put("join_age_min", joinAgeMin);
        product.put("join_age_max", joinAgeMax);
        product.put("age_subject_confidence", ageSubjectConfidence);
        return product;
    }

    @Test
    @DisplayName("petType에 해당하는 상품이 없으면 빈 목록을 반환한다")
    void should_returnEmptyList_whenNoProductsMatchSpecies() {
        service = new InsuranceProductServiceImpl(insuranceMapper);
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of());

        List<ProductResponse> result = service.getProducts("CAT", null, "premium_asc");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("ageSubjectConfidence=CONFIRMED이고 나이 범위 밖이면 제외한다")
    void should_excludeProduct_whenConfirmedAgeMismatch() {
        service = new InsuranceProductServiceImpl(insuranceMapper);
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(1L, new BigDecimal("10000"), 1, 19, "CONFIRMED")));
        when(insuranceMapper.findCoveragesByProductIds(List.of(1L))).thenReturn(List.of());
        when(insuranceMapper.findPlanTiersByProductIds(List.of(1L))).thenReturn(List.of());

        List<ProductResponse> result = service.getProducts("CAT", 25, "premium_asc");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("ageSubjectConfidence가 CONFIRMED가 아니면 나이 범위 밖이어도 제외하지 않고 정렬 순위만 낮춘다")
    void should_rankLower_notExclude_whenNonConfirmedAgeMismatch() {
        service = new InsuranceProductServiceImpl(insuranceMapper);
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(1L, new BigDecimal("5000"), 1, 19, "UNKNOWN_DEFAULT"),
                product(2L, new BigDecimal("30000"), 0, 80, "UNKNOWN_DEFAULT")));
        when(insuranceMapper.findCoveragesByProductIds(List.of(1L, 2L))).thenReturn(List.of());
        when(insuranceMapper.findPlanTiersByProductIds(List.of(1L, 2L))).thenReturn(List.of());

        List<ProductResponse> result = service.getProducts("CAT", 25, "premium_asc");

        assertEquals(2, result.size());
        // 나이 범위(0~80)에 실제로 맞는 상품 2가 저렴한 상품 1보다 우선 노출된다
        assertEquals("2", result.get(0).getProductId());
        assertEquals("1", result.get(1).getProductId());
    }

    @Test
    @DisplayName("age 파라미터가 없으면 premium 기준으로만 정렬한다")
    void should_sortByPremiumOnly_whenAgeParamAbsent() {
        service = new InsuranceProductServiceImpl(insuranceMapper);
        when(insuranceMapper.findProductsBySpecies("DOG")).thenReturn(List.of(
                product(1L, new BigDecimal("40000"), 0, 80, "CONFIRMED"),
                product(2L, new BigDecimal("10000"), 0, 80, "CONFIRMED")));
        when(insuranceMapper.findCoveragesByProductIds(List.of(1L, 2L))).thenReturn(List.of());
        when(insuranceMapper.findPlanTiersByProductIds(List.of(1L, 2L))).thenReturn(List.of());

        List<ProductResponse> result = service.getProducts("DOG", null, "premium_desc");

        assertEquals("1", result.get(0).getProductId());
        assertEquals("2", result.get(1).getProductId());
    }

    @Test
    @DisplayName("담보와 플랜티어를 productId 기준으로 묶어서 응답에 포함한다")
    void should_groupCoveragesAndPlanTiers_byProductId() {
        service = new InsuranceProductServiceImpl(insuranceMapper);
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(1L, new BigDecimal("10000"), 0, 80, "CONFIRMED")));

        Map<String, Object> coverage = new HashMap<>();
        coverage.put("product_id", 1L);
        coverage.put("coverage_name", "상해 후유장해(80%이상)");
        coverage.put("coverage_amount", "1000만원");
        coverage.put("medical_category", "OTHER");
        when(insuranceMapper.findCoveragesByProductIds(List.of(1L))).thenReturn(List.of(coverage));

        Map<String, Object> tier = new HashMap<>();
        tier.put("product_id", 1L);
        tier.put("tier_name", "50%보상형");
        tier.put("reimbursement_rate_pct", 50);
        tier.put("is_reference_tier", 1);
        tier.put("premium_krw", new BigDecimal("21541"));
        when(insuranceMapper.findPlanTiersByProductIds(List.of(1L))).thenReturn(List.of(tier));

        List<ProductResponse> result = service.getProducts("CAT", null, "premium_asc");

        assertEquals(1, result.get(0).getCoverages().size());
        assertEquals("상해 후유장해(80%이상)", result.get(0).getCoverages().get(0).getCoverageName());
        assertEquals(1, result.get(0).getPlanTiers().size());
        assertEquals(Boolean.TRUE, result.get(0).getPlanTiers().get(0).getIsReferenceTier());
    }

    @Test
    @DisplayName("가입연령 정보가 없는 상품은 age 파라미터가 있어도 제외되지 않는다")
    void should_includeProduct_whenJoinAgeRangeIsMissing() {
        service = new InsuranceProductServiceImpl(insuranceMapper);
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(1L, new BigDecimal("10000"), null, null, "CONFIRMED")));
        when(insuranceMapper.findCoveragesByProductIds(List.of(1L))).thenReturn(List.of());
        when(insuranceMapper.findPlanTiersByProductIds(List.of(1L))).thenReturn(List.of());

        List<ProductResponse> result = service.getProducts("CAT", 25, "premium_asc");

        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("is_reference_tier가 Boolean 타입이거나 없을 때도 안전하게 변환한다")
    void should_convertIsReferenceTier_whenBooleanTypeOrMissing() {
        service = new InsuranceProductServiceImpl(insuranceMapper);
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(1L, new BigDecimal("10000"), 0, 80, "CONFIRMED")));
        when(insuranceMapper.findCoveragesByProductIds(List.of(1L))).thenReturn(List.of());

        Map<String, Object> tierWithBoolean = new HashMap<>();
        tierWithBoolean.put("product_id", 1L);
        tierWithBoolean.put("tier_name", "50%보상형");
        tierWithBoolean.put("reimbursement_rate_pct", 50);
        tierWithBoolean.put("is_reference_tier", Boolean.TRUE);
        tierWithBoolean.put("premium_krw", new BigDecimal("21541"));

        Map<String, Object> tierWithMissingFlag = new HashMap<>();
        tierWithMissingFlag.put("product_id", 1L);
        tierWithMissingFlag.put("tier_name", "70%보상형");
        tierWithMissingFlag.put("reimbursement_rate_pct", 70);
        tierWithMissingFlag.put("is_reference_tier", null);
        tierWithMissingFlag.put("premium_krw", null);

        when(insuranceMapper.findPlanTiersByProductIds(List.of(1L)))
                .thenReturn(List.of(tierWithBoolean, tierWithMissingFlag));

        List<ProductResponse> result = service.getProducts("CAT", null, "premium_asc");

        assertEquals(Boolean.TRUE, result.get(0).getPlanTiers().get(0).getIsReferenceTier());
        assertEquals(null, result.get(0).getPlanTiers().get(1).getIsReferenceTier());
    }
}
