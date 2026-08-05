package com.aewol.domain.insurance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.insurance.dto.SimulationRequest;
import com.aewol.domain.insurance.dto.SimulationResponse;
import com.aewol.domain.insurance.mapper.InsuranceMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InsuranceSimulationServiceImplTest {

    private static final String MEMBER_ID = "10";

    @Mock InsuranceMapper insuranceMapper;
    @Mock PetMapper petMapper;

    private InsuranceSimulationServiceImpl service;

    private SimulationRequest request(String petId, List<String> medicalHistoryCodes) {
        SimulationRequest request = new SimulationRequest();
        ReflectionTestUtils.setField(request, "petId", petId);
        ReflectionTestUtils.setField(request, "medicalHistoryCodes", medicalHistoryCodes);
        return request;
    }

    private Map<String, Object> pet(String species, int age) {
        Map<String, Object> pet = new HashMap<>();
        pet.put("species", species);
        pet.put("birth_date", java.sql.Date.valueOf(LocalDate.now().minusYears(age)));
        return pet;
    }

    private Map<String, Object> product(long productId, BigDecimal monthlyPremium, Integer joinAgeMin,
            Integer joinAgeMax, Integer reimbursementRatePct, String reimbursementConfidence) {
        Map<String, Object> product = new HashMap<>();
        product.put("product_id", productId);
        product.put("company_name", "삼성화재");
        product.put("product_name", "무배당 삼성화재 다이렉트 반려묘보험");
        product.put("premium_monthly_equiv", monthlyPremium);
        product.put("join_age_min", joinAgeMin);
        product.put("join_age_max", joinAgeMax);
        product.put("reimbursement_structure", "BUNDLED_TIER");
        product.put("reimbursement_rate_pct", reimbursementRatePct);
        product.put("reimbursement_confidence", reimbursementConfidence);
        product.put("regulatory_cap_warning", null);
        return product;
    }

    @Test
    @DisplayName("Notion 명세 예시 수치대로 손익분기 시나리오(1/5/10년)를 계산한다")
    void should_calculateBreakEvenScenarios_matchingSpecExample() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(3L, new BigDecimal("21541"), 0, 80, 50, "CONFIRMED_OWN_COVERAGE_NAME")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertEquals(1, response.getRecommendedProducts().size());
        var breakEvenScenarios = response.getRecommendedProducts().get(0).getBreakEvenScenarios();
        assertEquals(258492L, breakEvenScenarios.get(0).getCumulativePremiumKrw());
        assertEquals(256750L, breakEvenScenarios.get(0).getExpectedReimbursementKrw());
        assertEquals(-1742L, breakEvenScenarios.get(0).getDifferenceKrw());
        assertEquals(Boolean.FALSE, breakEvenScenarios.get(0).getIsFavorable());
        assertEquals(1292460L, breakEvenScenarios.get(1).getCumulativePremiumKrw());
        assertEquals(1283750L, breakEvenScenarios.get(1).getExpectedReimbursementKrw());
        assertEquals(2584920L, breakEvenScenarios.get(2).getCumulativePremiumKrw());
        assertEquals(2567500L, breakEvenScenarios.get(2).getExpectedReimbursementKrw());
    }

    @Test
    @DisplayName("가입 연령 범위 밖의 상품은 추천 목록에서 제외한다")
    void should_excludeProduct_whenPetAgeOutsideJoinAgeRange() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("DOG", 25));
        when(insuranceMapper.findProductsBySpecies("DOG")).thenReturn(List.of(
                product(1L, new BigDecimal("10000"), 1, 19, 50, "CONFIRMED_OWN_COVERAGE_NAME")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertTrue(response.getRecommendedProducts().isEmpty());
    }

    @Test
    @DisplayName("reimbursementConfidence가 UNVERIFIED인 상품은 추천 목록에서 제외한다")
    void should_excludeProduct_whenReimbursementConfidenceIsUnverified() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("DOG", 3));
        when(insuranceMapper.findProductsBySpecies("DOG")).thenReturn(List.of(
                product(1L, new BigDecimal("500"), 0, 20, null, "UNVERIFIED")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertTrue(response.getRecommendedProducts().isEmpty());
    }

    @Test
    @DisplayName("reimbursementRatePct가 없는 상품은 손익분기를 계산하지 않고 breakEvenAvailable=false로 반환한다")
    void should_markBreakEvenUnavailable_whenReimbursementRateMissing() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 1));
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(5L, new BigDecimal("31815"), 1, 19, null, "ASSUMED_FROM_RESEARCH")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        var recommended = response.getRecommendedProducts().get(0);
        assertEquals(false, recommended.isBreakEvenAvailable());
        assertNull(recommended.getBreakEvenScenarios());
    }

    @Test
    @DisplayName("병력코드가 NONE 외에 포함되면 preExistingConditionWarning을 항상 포함한다")
    void should_includePreExistingConditionWarning_whenMedicalHistoryCodesNotNone() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of());

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("JOINT")));

        assertEquals(
                "기존 병력이 있는 경우 일부 보장이 제한되거나 면책될 수 있습니다. 가입 전 약관을 확인하세요.",
                response.getPreExistingConditionWarning());
    }

    @Test
    @DisplayName("추천 상품이 0건이면 NEUTRAL 안내 문구를 반환한다")
    void should_returnNeutralAdvice_whenNoRecommendedProducts() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of());

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertEquals("NEUTRAL", response.getInsuranceAdvice().getVerdict());
        verify(insuranceMapper).insertSimulation(any());
    }

    @Test
    @DisplayName("존재하지 않는 반려동물이면 404 예외를 던진다")
    void should_throwNotFound_whenPetDoesNotExist() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("999", MEMBER_ID)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> service.simulate(MEMBER_ID, request("999", List.of("NONE"))));
    }

    @Test
    @DisplayName("다른 회원 소유의 반려동물이면 404 예외를 던진다 (IDOR 방지)")
    void should_throwNotFound_whenPetBelongsToAnotherMember() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        // pet_id=3은 실제로 존재하지만 다른 회원 소유라, memberId까지 일치하는 조회는 null을 반환해야 한다
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> service.simulate(MEMBER_ID, request("3", List.of("NONE"))));
    }

    @Test
    @DisplayName("생년월일이 java.sql.Date가 아닌 문자열로 와도 나이를 계산한다")
    void should_calculateAge_whenBirthDateIsPlainStringNotSqlDate() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        Map<String, Object> petWithStringBirthDate = new HashMap<>();
        petWithStringBirthDate.put("species", "DOG");
        petWithStringBirthDate.put("birth_date", LocalDate.now().minusYears(25).toString());
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(petWithStringBirthDate);
        when(insuranceMapper.findProductsBySpecies("DOG")).thenReturn(List.of(
                product(1L, new BigDecimal("10000"), 1, 19, 50, "CONFIRMED_OWN_COVERAGE_NAME")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertTrue(response.getRecommendedProducts().isEmpty());
    }

    @Test
    @DisplayName("반려동물 생년월일이 없으면 나이 필터 없이 모든 상품을 대상으로 한다")
    void should_skipAgeFilter_whenPetBirthDateIsNull() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        Map<String, Object> petWithoutBirthDate = new HashMap<>();
        petWithoutBirthDate.put("species", "DOG");
        petWithoutBirthDate.put("birth_date", null);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(petWithoutBirthDate);
        when(insuranceMapper.findProductsBySpecies("DOG")).thenReturn(List.of(
                product(1L, new BigDecimal("10000"), 10, 15, 50, "CONFIRMED_OWN_COVERAGE_NAME")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertEquals(1, response.getRecommendedProducts().size());
    }

    @Test
    @DisplayName("상품의 가입연령 정보가 없으면 나이와 무관하게 후보에 포함한다")
    void should_includeProduct_whenJoinAgeRangeIsMissing() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("DOG", 3));
        when(insuranceMapper.findProductsBySpecies("DOG")).thenReturn(List.of(
                product(1L, new BigDecimal("10000"), null, null, 50, "CONFIRMED_OWN_COVERAGE_NAME")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertEquals(1, response.getRecommendedProducts().size());
    }

    @Test
    @DisplayName("추천 상품의 과반이 5년차 기준 유리하면 FAVORABLE로 판정한다")
    void should_returnFavorableAdvice_whenMajorityOfProductsAreFavorableAtYearFive() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        // 5년 누적보험료(60개월치) < 5년 예상보장금(annualExpectedVetCostKrw*rate/100*5) 이 되도록
        // 아주 저렴한 보험료의 상품을 구성해 favorable하게 만든다.
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(1L, new BigDecimal("100"), 0, 80, 90, "CONFIRMED_OWN_COVERAGE_NAME")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertEquals("FAVORABLE", response.getInsuranceAdvice().getVerdict());
    }

    @Test
    @DisplayName("추천 상품 중 일부만 5년차 기준 유리하면 NEUTRAL로 판정한다")
    void should_returnNeutralAdvice_whenSomeButNotMajorityOfProductsAreFavorable() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                // favorable: 저렴한 보험료 + 높은 보장비율
                product(1L, new BigDecimal("100"), 0, 80, 90, "CONFIRMED_OWN_COVERAGE_NAME"),
                // unfavorable: 비싼 보험료 + 낮은 보장비율
                product(2L, new BigDecimal("100000"), 0, 80, 10, "CONFIRMED_OWN_COVERAGE_NAME"),
                // unfavorable: 비싼 보험료 + 낮은 보장비율
                product(3L, new BigDecimal("100000"), 0, 80, 10, "CONFIRMED_OWN_COVERAGE_NAME")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertEquals("NEUTRAL", response.getInsuranceAdvice().getVerdict());
    }

    @Test
    @DisplayName("추천 상품 전부 손익분기 계산이 불가하면 UNFAVORABLE이 아니라 NEUTRAL로 판정한다")
    void should_returnNeutralAdvice_whenNoRecommendedProductHasBreakEvenData() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("DOG", 3));
        // 둘 다 reimbursementRatePct가 없어 breakEvenAvailable=false — 비교 자체를 못 한 상태
        when(insuranceMapper.findProductsBySpecies("DOG")).thenReturn(List.of(
                product(1L, new BigDecimal("6193"), 0, 80, null, "ASSUMED_FROM_RESEARCH"),
                product(2L, new BigDecimal("29921"), 0, 80, null, "ASSUMED_FROM_RESEARCH")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertEquals("NEUTRAL", response.getInsuranceAdvice().getVerdict());
    }

    @Test
    @DisplayName("손익분기 계산 가능 상품 중 정확히 절반만 유리하면 FAVORABLE이 아니라 NEUTRAL로 판정한다")
    void should_returnNeutralAdvice_whenExactlyHalfOfCalculableProductsAreFavorable() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                // favorable
                product(1L, new BigDecimal("100"), 0, 80, 90, "CONFIRMED_OWN_COVERAGE_NAME"),
                // unfavorable
                product(2L, new BigDecimal("100000"), 0, 80, 10, "CONFIRMED_OWN_COVERAGE_NAME")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertEquals("NEUTRAL", response.getInsuranceAdvice().getVerdict());
    }
}
