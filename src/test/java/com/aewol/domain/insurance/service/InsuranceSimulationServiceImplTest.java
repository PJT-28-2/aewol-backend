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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ⚠️ V28 재설계(worker-4): 환급률/자기부담금/연간한도/출처/신뢰도의 진실 원천이
 * insurance_product에서 insurance_product_plan_tiers의 "견적 기준 티어"
 * (is_reference_tier=1)로 이전됐다. 이 테스트의 {@code product(...)} 헬퍼는 더 이상
 * reimbursement_* 컬럼을 담지 않으며, 대신 {@code referenceTier(...)} 헬퍼로 만든
 * 티어 맵을 {@code insuranceMapper.findReferenceTiersByProductIds(...)}에 별도로
 * 스텁해야 한다. 티어를 스텁하지 않으면(Mockito 기본값=빈 리스트) 해당 상품은
 * "아직 리서치되지 않은 상품"과 동일하게 취급되어 rate=null, breakEvenAvailable=false가
 * 된다 — 이는 실제 운영 상태(19개 상품이 아직 plan_tiers에 행이 없음)와 일치한다.
 */
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
            Integer joinAgeMax) {
        Map<String, Object> product = new HashMap<>();
        product.put("product_id", productId);
        product.put("company_name", "삼성화재");
        product.put("product_name", "무배당 삼성화재 다이렉트 반려묘보험");
        product.put("premium_monthly_equiv", monthlyPremium);
        product.put("join_age_min", joinAgeMin);
        product.put("join_age_max", joinAgeMax);
        product.put("reimbursement_structure", "BUNDLED_TIER");
        product.put("regulatory_cap_warning", null);
        // S7: 후보 필터에 product_category='MEDICAL'이 추가되어, 기본값을 넣지 않으면
        // 이 헬퍼로 만든 모든 상품이 후보에서 하드 배제된다. LIABILITY_TRAVEL 전용 테스트만
        // 개별적으로 put("product_category", "LIABILITY_TRAVEL")로 덮어쓴다.
        product.put("product_category", "MEDICAL");
        return product;
    }

    /** insurance_product_plan_tiers의 견적 기준 티어(is_reference_tier=1) 행을 흉내낸다. */
    private Map<String, Object> referenceTier(long productId, Integer reimbursementRatePct,
            String reimbursementConfidence) {
        Map<String, Object> tier = new HashMap<>();
        tier.put("product_id", productId);
        tier.put("is_reference_tier", 1);
        tier.put("reimbursement_rate_pct", reimbursementRatePct);
        tier.put("reimbursement_confidence", reimbursementConfidence);
        return tier;
    }

    @Test
    @DisplayName("Notion 명세 예시 수치대로 손익분기 시나리오(1/5/10년)를 계산한다 — 견적 기준 티어에서 환급률을 읽는다")
    void should_calculateBreakEvenScenarios_matchingSpecExample() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(3L, new BigDecimal("21541"), 0, 80)));
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(3L))).thenReturn(List.of(
                referenceTier(3L, 50, "CONFIRMED_OWN_COVERAGE_NAME")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertEquals(1, response.getRecommendedProducts().size());
        var recommended = response.getRecommendedProducts().get(0);
        assertEquals(50, recommended.getReimbursementRatePct());
        assertEquals("CONFIRMED_OWN_COVERAGE_NAME", recommended.getReimbursementConfidence());
        var breakEvenScenarios = recommended.getBreakEvenScenarios();
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
    @DisplayName("상품에 견적 기준 티어 행이 아예 없으면(리서치 미착수) breakEvenAvailable=false, confidence=null로 반환한다")
    void should_markUnavailableWithNullConfidence_whenNoReferenceTierRowExists() {
        // Mockito 기본값(빈 리스트)을 그대로 사용 — insuranceMapper.findReferenceTiersByProductIds를
        // 스텁하지 않는다. 이는 아직 리서치되지 않은 19개 상품의 실제 상태(plan_tiers 0행)와 같다.
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("DOG", 3));
        when(insuranceMapper.findProductsBySpecies("DOG")).thenReturn(List.of(
                product(1L, new BigDecimal("29921"), 0, 80)));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertEquals(1, response.getRecommendedProducts().size());
        var recommended = response.getRecommendedProducts().get(0);
        assertEquals(false, recommended.isBreakEvenAvailable());
        assertNull(recommended.getReimbursementRatePct());
        assertNull(recommended.getReimbursementConfidence());
        assertNull(recommended.getBreakEvenScenarios());
    }

    @Test
    @DisplayName("가입 연령 범위 밖의 상품은 추천 목록에서 제외한다")
    void should_excludeProduct_whenPetAgeOutsideJoinAgeRange() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("DOG", 25));
        when(insuranceMapper.findProductsBySpecies("DOG")).thenReturn(List.of(
                product(1L, new BigDecimal("10000"), 1, 19)));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertTrue(response.getRecommendedProducts().isEmpty());
    }

    @Test
    @DisplayName("AC-5 서비스 레벨: age_basis=OWNER + join_age_min=18 상품은 3세 펫의 추천 후보에 포함된다")
    void should_includeProduct_whenAgeBasisOwnerAndJoinAgeMinAboveThreePetAge() {
        // 메리츠(18~80, OWNER) 형태의 상품이 3세 펫에게 하드 배제되지 않아야 한다 (RC-2 복귀 확인).
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("DOG", 3));
        Map<String, Object> ownerBasisProduct = product(5L, new BigDecimal("33971"), 18, 80);
        ownerBasisProduct.put("age_basis", "OWNER");
        when(insuranceMapper.findProductsBySpecies("DOG")).thenReturn(List.of(ownerBasisProduct));
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(5L))).thenReturn(List.of(
                referenceTier(5L, 70, "CONFIRMED_OWN_COVERAGE_NAME")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertEquals(1, response.getRecommendedProducts().size());
        assertEquals("5", response.getRecommendedProducts().get(0).getProductId());
    }

    @Test
    @DisplayName("product_category=LIABILITY_TRAVEL 상품은 견적 기준 티어가 있어도 후보에서 제외한다")
    void should_excludeProduct_whenProductCategoryIsLiabilityTravel() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("DOG", 3));
        Map<String, Object> travelProduct = product(9L, new BigDecimal("502"), 0, 20);
        travelProduct.put("product_category", "LIABILITY_TRAVEL");
        when(insuranceMapper.findProductsBySpecies("DOG")).thenReturn(List.of(travelProduct));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertTrue(response.getRecommendedProducts().isEmpty());
    }

    @Test
    @DisplayName("reimbursementConfidence가 UNVERIFIED인 상품은 추천 목록에 남고 breakEvenAvailable=false로 반환된다")
    void should_keepProductInRecommendedList_whenReimbursementConfidenceIsUnverified() {
        // Decision 4(A): UNVERIFIED 필터를 제거해 근거 미확인 상품도 화면에 도달하게 한다.
        // 필터로 침묵시키면 "정직하게 비웠더니 화면이 비었다"(계획서 Pre-mortem 시나리오 2)가 재현된다.
        // AC-1 수정안: 못 찾은 상품도 confidence='UNVERIFIED' 티어 행 자체는 기록된다(빈칸이 아니라 명시적 미확인).
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("DOG", 3));
        when(insuranceMapper.findProductsBySpecies("DOG")).thenReturn(List.of(
                product(1L, new BigDecimal("500"), 0, 20)));
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(1L))).thenReturn(List.of(
                referenceTier(1L, null, "UNVERIFIED")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertEquals(1, response.getRecommendedProducts().size());
        var recommended = response.getRecommendedProducts().get(0);
        assertEquals("UNVERIFIED", recommended.getReimbursementConfidence());
        assertEquals(false, recommended.isBreakEvenAvailable());
        assertNull(recommended.getBreakEvenScenarios());
    }

    @Test
    @DisplayName("breakEvenAvailable=true인 상품이 보험료가 더 비싸도 미확인 상품보다 먼저 정렬된다")
    void should_sortBreakEvenAvailableProductsFirst_regardlessOfPremium() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        // 1L: 훨씬 저렴하지만 견적 기준 티어 없음(미확인) / 2L: 비싸지만 환급률 확인됨
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(1L, new BigDecimal("100"), 0, 80),
                product(2L, new BigDecimal("1000000"), 0, 80)));
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(1L, 2L))).thenReturn(List.of(
                referenceTier(2L, 50, "CONFIRMED_OWN_COVERAGE_NAME")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertEquals(2, response.getRecommendedProducts().size());
        assertEquals("2", response.getRecommendedProducts().get(0).getProductId());
        assertEquals(true, response.getRecommendedProducts().get(0).isBreakEvenAvailable());
        assertEquals("1", response.getRecommendedProducts().get(1).getProductId());
        assertEquals(false, response.getRecommendedProducts().get(1).isBreakEvenAvailable());
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
                product(1L, new BigDecimal("10000"), 1, 19)));

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
                product(1L, new BigDecimal("10000"), 10, 15)));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertEquals(1, response.getRecommendedProducts().size());
    }

    @Test
    @DisplayName("상품의 가입연령 정보가 없으면 나이와 무관하게 후보에 포함한다")
    void should_includeProduct_whenJoinAgeRangeIsMissing() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("DOG", 3));
        when(insuranceMapper.findProductsBySpecies("DOG")).thenReturn(List.of(
                product(1L, new BigDecimal("10000"), null, null)));

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
                product(1L, new BigDecimal("100"), 0, 80)));
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(1L))).thenReturn(List.of(
                referenceTier(1L, 90, "CONFIRMED_OWN_COVERAGE_NAME")));

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
                product(1L, new BigDecimal("100"), 0, 80),
                // unfavorable: 비싼 보험료 + 낮은 보장비율
                product(2L, new BigDecimal("100000"), 0, 80),
                // unfavorable: 비싼 보험료 + 낮은 보장비율
                product(3L, new BigDecimal("100000"), 0, 80)));
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(1L, 2L, 3L))).thenReturn(List.of(
                referenceTier(1L, 90, "CONFIRMED_OWN_COVERAGE_NAME"),
                referenceTier(2L, 10, "CONFIRMED_OWN_COVERAGE_NAME"),
                referenceTier(3L, 10, "CONFIRMED_OWN_COVERAGE_NAME")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertEquals("NEUTRAL", response.getInsuranceAdvice().getVerdict());
    }

    @Test
    @DisplayName("추천 상품 전부 손익분기 계산이 불가하면 UNFAVORABLE이 아니라 NEUTRAL로 판정한다")
    void should_returnNeutralAdvice_whenNoRecommendedProductHasBreakEvenData() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("DOG", 3));
        // 둘 다 견적 기준 티어의 rate가 없어 breakEvenAvailable=false — 비교 자체를 못 한 상태
        when(insuranceMapper.findProductsBySpecies("DOG")).thenReturn(List.of(
                product(1L, new BigDecimal("6193"), 0, 80),
                product(2L, new BigDecimal("29921"), 0, 80)));
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(1L, 2L))).thenReturn(List.of(
                referenceTier(1L, null, "ASSUMED_FROM_RESEARCH"),
                referenceTier(2L, null, "ASSUMED_FROM_RESEARCH")));

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
                product(1L, new BigDecimal("100"), 0, 80),
                // unfavorable
                product(2L, new BigDecimal("100000"), 0, 80)));
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(1L, 2L))).thenReturn(List.of(
                referenceTier(1L, 90, "CONFIRMED_OWN_COVERAGE_NAME"),
                referenceTier(2L, 10, "CONFIRMED_OWN_COVERAGE_NAME")));

        SimulationResponse response = service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        assertEquals("NEUTRAL", response.getInsuranceAdvice().getVerdict());
    }

    @Test
    @DisplayName("Decision 5: deductible_basis=PER_YEAR면 n(연 진료 횟수) 확보 여부와 무관하게 자기부담금을 반영한다")
    void should_applyDeductible_whenBasisIsPerYear_regardlessOfClaimCountSource() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(1L, new BigDecimal("10000"), 0, 80)));
        Map<String, Object> tier = referenceTier(1L, 50, "CONFIRMED_OWN_COVERAGE_NAME");
        tier.put("deductible_krw", new BigDecimal("100000"));
        tier.put("deductible_basis", "PER_YEAR");
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(1L))).thenReturn(List.of(tier));

        SimulationRequest request = request("3", List.of("NONE"));
        ReflectionTestUtils.setField(request, "annualMedicalCostKrw", 500_000L);
        SimulationResponse response = service.simulate(MEMBER_ID, request);

        // max(0, 500000-100000) * 0.5 = 200000
        assertEquals(200_000L, response.getRecommendedProducts().get(0).getBreakEvenScenarios().get(0)
                .getExpectedReimbursementKrw());
    }

    @Test
    @DisplayName("Decision 5: deductible_basis=PER_VISIT이고 n 미확보면 해당 상품만 자기부담금을 반영하지 않는다")
    void should_notApplyDeductible_whenBasisIsPerVisitAndClaimCountUnconfirmed() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(1L, new BigDecimal("10000"), 0, 80)));
        Map<String, Object> tier = referenceTier(1L, 50, "CONFIRMED_OWN_COVERAGE_NAME");
        tier.put("deductible_krw", new BigDecimal("30000"));
        tier.put("deductible_basis", "PER_VISIT");
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(1L))).thenReturn(List.of(tier));

        SimulationRequest request = request("3", List.of("NONE"));
        ReflectionTestUtils.setField(request, "annualMedicalCostKrw", 500_000L);
        SimulationResponse response = service.simulate(MEMBER_ID, request);

        // n 미확보 + PER_VISIT: 자기부담금 미반영, 500000 * 0.5 = 250000
        assertEquals(250_000L, response.getRecommendedProducts().get(0).getBreakEvenScenarios().get(0)
                .getExpectedReimbursementKrw());
    }

    @Test
    @DisplayName("Decision 5: deductible_basis가 NULL이면 PER_YEAR로 가정하지 않고 자기부담금을 반영하지 않는다")
    void should_notApplyDeductible_whenBasisIsNull() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(1L, new BigDecimal("10000"), 0, 80)));
        // 메리츠 실데이터와 같은 형태: 금액은 확인됐지만 건당/연간 기준은 원본에 없어 미확인
        Map<String, Object> tier = referenceTier(1L, 50, "CONFIRMED_OWN_COVERAGE_NAME");
        tier.put("deductible_krw", new BigDecimal("30000"));
        tier.put("deductible_basis", null);
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(1L))).thenReturn(List.of(tier));

        SimulationRequest request = request("3", List.of("NONE"));
        ReflectionTestUtils.setField(request, "annualMedicalCostKrw", 500_000L);
        SimulationResponse response = service.simulate(MEMBER_ID, request);

        // basis 미확인 → 미반영: 500000 * 0.5 = 250000
        // (PER_YEAR로 가정했다면 (500000-30000)*0.5 = 235000 이 되어 보장금이 과대 계산된다)
        var recommended = response.getRecommendedProducts().get(0);
        assertEquals(250_000L, recommended.getBreakEvenScenarios().get(0).getExpectedReimbursementKrw());
        assertEquals(Boolean.FALSE, recommended.getDeductibleApplied());
    }

    @Test
    @DisplayName("자기부담금이 반영된 상품은 deductibleApplied=true로 내려간다")
    void should_markDeductibleApplied_whenBasisIsKnown() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(1L, new BigDecimal("10000"), 0, 80)));
        Map<String, Object> tier = referenceTier(1L, 50, "CONFIRMED_OWN_COVERAGE_NAME");
        tier.put("deductible_krw", new BigDecimal("100000"));
        tier.put("deductible_basis", "PER_YEAR");
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(1L))).thenReturn(List.of(tier));

        SimulationRequest request = request("3", List.of("NONE"));
        ReflectionTestUtils.setField(request, "annualMedicalCostKrw", 500_000L);
        SimulationResponse response = service.simulate(MEMBER_ID, request);

        assertEquals(Boolean.TRUE, response.getRecommendedProducts().get(0).getDeductibleApplied());
    }

    @Test
    @DisplayName("Decision 5: deductible_krw가 NULL이면 자기부담금 없이 그대로 적용한다")
    void should_applyRateDirectly_whenDeductibleIsNull() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(1L, new BigDecimal("10000"), 0, 80)));
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(1L))).thenReturn(List.of(
                referenceTier(1L, 50, "CONFIRMED_OWN_COVERAGE_NAME")));

        SimulationRequest request = request("3", List.of("NONE"));
        ReflectionTestUtils.setField(request, "annualMedicalCostKrw", 500_000L);
        SimulationResponse response = service.simulate(MEMBER_ID, request);

        assertEquals(250_000L, response.getRecommendedProducts().get(0).getBreakEvenScenarios().get(0)
                .getExpectedReimbursementKrw());
    }

    @Test
    @DisplayName("Decision 5: deductible_order=AFTER_RATE면 보장률 선적용 후 자기부담금을 차감한다")
    void should_reverseOrder_whenDeductibleOrderIsAfterRate() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        Map<String, Object> product = product(1L, new BigDecimal("10000"), 0, 80);
        product.put("deductible_order", "AFTER_RATE");
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(product));
        Map<String, Object> tier = referenceTier(1L, 50, "CONFIRMED_OWN_COVERAGE_NAME");
        tier.put("deductible_krw", new BigDecimal("100000"));
        tier.put("deductible_basis", "PER_YEAR");
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(1L))).thenReturn(List.of(tier));

        SimulationRequest request = request("3", List.of("NONE"));
        ReflectionTestUtils.setField(request, "annualMedicalCostKrw", 500_000L);
        SimulationResponse response = service.simulate(MEMBER_ID, request);

        // 500000*0.5=250000 - 100000 = 150000 (선차감 방식 200000과 다름을 확인)
        assertEquals(150_000L, response.getRecommendedProducts().get(0).getBreakEvenScenarios().get(0)
                .getExpectedReimbursementKrw());
    }

    @Test
    @DisplayName("expectedReimbursement은 자기부담금이 연 의료비보다 커도 음수가 되지 않는다")
    void should_notReturnNegativeExpectedReimbursement_whenDeductibleExceedsAnnualCost() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(1L, new BigDecimal("10000"), 0, 80)));
        Map<String, Object> tier = referenceTier(1L, 50, "CONFIRMED_OWN_COVERAGE_NAME");
        tier.put("deductible_krw", new BigDecimal("5000000"));
        tier.put("deductible_basis", "PER_YEAR");
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(1L))).thenReturn(List.of(tier));

        SimulationRequest request = request("3", List.of("NONE"));
        ReflectionTestUtils.setField(request, "annualMedicalCostKrw", 100_000L);
        SimulationResponse response = service.simulate(MEMBER_ID, request);

        long expectedReimbursement = response.getRecommendedProducts().get(0).getBreakEvenScenarios().get(0)
                .getExpectedReimbursementKrw();
        assertEquals(0L, expectedReimbursement);
        assertTrue(expectedReimbursement >= 0);
    }

    @Test
    @DisplayName("대표 상품 선정: breakEvenAvailable=true인 상품 중 보험료 최저가가 대표 상품으로 저장된다")
    void should_selectCheapestCalculableProduct_asRepresentativeProduct() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        // 1L: 계산 불가(미확인)지만 가장 저렴 / 2L, 3L: 계산 가능, 3L이 더 저렴
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(1L, new BigDecimal("50"), 0, 80),
                product(2L, new BigDecimal("200"), 0, 80),
                product(3L, new BigDecimal("100"), 0, 80)));
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(1L, 2L, 3L))).thenReturn(List.of(
                referenceTier(2L, 50, "CONFIRMED_OWN_COVERAGE_NAME"),
                referenceTier(3L, 70, "CONFIRMED_OWN_COVERAGE_NAME")));

        service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(insuranceMapper).insertSimulation(captor.capture());
        // 계산 가능한 2L(200원)·3L(100원) 중 최저가인 3L이 대표 상품이어야 한다.
        // 계산 불가한 1L(50원)이 전체 최저가지만 대표로 선정되지 않아야 한다 (M-8).
        assertEquals(3L, captor.getValue().get("representativeProductId"));
    }

    @Test
    @DisplayName("대표 상품 선정: 계산 가능한 상품이 0건이면 대표 상품 ID/라벨이 NULL로 저장된다")
    void should_persistNullRepresentativeProduct_whenNoCalculableProduct() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("DOG", 3));
        when(insuranceMapper.findProductsBySpecies("DOG")).thenReturn(List.of(
                product(1L, new BigDecimal("6193"), 0, 80)));
        // 견적 기준 티어를 스텁하지 않음 = 리서치 미착수 상태(rate=null)

        service.simulate(MEMBER_ID, request("3", List.of("NONE")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(insuranceMapper).insertSimulation(captor.capture());
        assertNull(captor.getValue().get("representativeProductId"));
        assertNull(captor.getValue().get("representativeProductLabel"));
    }

    @Test
    @DisplayName("persistSimulation이 가정치·조정여부·대표상품 ID/라벨을 함께 저장한다")
    void should_persistAssumptionsAndRepresentativeProduct() {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of(
                product(1L, new BigDecimal("21541"), 0, 80)));
        when(insuranceMapper.findReferenceTiersByProductIds(List.of(1L))).thenReturn(List.of(
                referenceTier(1L, 50, "CONFIRMED_OWN_COVERAGE_NAME")));

        SimulationRequest request = request("3", List.of("NONE"));
        ReflectionTestUtils.setField(request, "annualMedicalCostKrw", 400_000L);
        service.simulate(MEMBER_ID, request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(insuranceMapper).insertSimulation(captor.capture());
        Map<String, Object> saved = captor.getValue();
        assertEquals(400_000L, saved.get("assumedAnnualMedicalCostKrw"));
        assertEquals(Boolean.TRUE, saved.get("isUserAdjusted"));
        assertEquals(1L, saved.get("representativeProductId"));
        assertTrue(((String) saved.get("representativeProductLabel")).contains("삼성화재"));
    }

    @Test
    @DisplayName("병력 코드에 따옴표/백슬래시가 섞여도 유효한 JSON으로 이스케이프해서 저장한다")
    void should_escapeSpecialCharacters_whenPersistingMedicalHistoryCodes() throws Exception {
        service = new InsuranceSimulationServiceImpl(insuranceMapper, petMapper);
        when(petMapper.findByIdAndMemberId("3", MEMBER_ID)).thenReturn(pet("CAT", 3));
        when(insuranceMapper.findProductsBySpecies("CAT")).thenReturn(List.of());

        // 요소 하나에 따옴표+콤마, 다른 하나에 백슬래시가 섞인 값 — 문자열 이어붙이기였다면
        // 요청의 2개 요소가 저장 데이터에서는 개수/내용이 달라졌을 케이스
        List<String> trickyHistoryCodes = List.of("NONE\",\"JOINT", "back\\slash");

        service.simulate(MEMBER_ID, request("3", trickyHistoryCodes));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(insuranceMapper).insertSimulation(captor.capture());
        String storedJson = (String) captor.getValue().get("medicalHistoryCodes");

        List<String> roundTripped = new ObjectMapper()
                .readValue(storedJson, new TypeReference<List<String>>() {});
        assertEquals(trickyHistoryCodes, roundTripped);
    }
}
