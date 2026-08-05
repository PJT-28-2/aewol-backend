package com.aewol.domain.insurance.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.insurance.dto.BreakEvenScenario;
import com.aewol.domain.insurance.dto.InsuranceAdvice;
import com.aewol.domain.insurance.dto.RecommendedProductResponse;
import com.aewol.domain.insurance.dto.SimulationAssumptions;
import com.aewol.domain.insurance.dto.SimulationRequest;
import com.aewol.domain.insurance.dto.SimulationResponse;
import com.aewol.domain.insurance.mapper.InsuranceMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InsuranceSimulationServiceImpl implements InsuranceSimulationService {

    private static final int ANNUAL_CLAIM_COUNT = 6;
    private static final long ANNUAL_EXPECTED_VET_COST_KRW = 513_500L;
    private static final String ASSUMPTION_SOURCE =
            "KB금융경영연구소 2025 한국 반려동물 보고서(가구 평균 2년 102.7만원 기준 연환산 추정치)";
    private static final String PRE_EXISTING_CONDITION_WARNING =
            "기존 병력이 있는 경우 일부 보장이 제한되거나 면책될 수 있습니다. 가입 전 약관을 확인하세요.";
    private static final int[] BREAK_EVEN_YEARS = {1, 5, 10};
    private static final int RECOMMENDED_PRODUCT_LIMIT = 5;

    private final InsuranceMapper insuranceMapper;
    private final PetMapper petMapper;

    @Override
    @Transactional
    public SimulationResponse simulate(String memberId, SimulationRequest request) {
        Map<String, Object> pet = petMapper.findByIdAndMemberId(request.getPetId(), memberId);
        if (pet == null) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
        String species = (String) pet.get("species");
        Integer age = calculateAge(pet.get("birth_date"));

        List<RecommendedProductResponse> recommendedProducts = insuranceMapper.findProductsBySpecies(species).stream()
                .filter(product -> ageInRange(product, age))
                .filter(product -> !"UNVERIFIED".equals(product.get("reimbursement_confidence")))
                .sorted(Comparator.comparing(product -> (BigDecimal) product.get("premium_monthly_equiv")))
                .limit(RECOMMENDED_PRODUCT_LIMIT)
                .map(this::toRecommendedProduct)
                .collect(Collectors.toList());

        List<String> historyCodes = request.getMedicalHistoryCodes() == null
                ? List.of() : request.getMedicalHistoryCodes();
        boolean hasPreExistingCondition = historyCodes.stream().anyMatch(code -> !"NONE".equals(code));

        InsuranceAdvice advice = buildAdvice(recommendedProducts);

        persistSimulation(request.getPetId(), historyCodes, advice);

        return SimulationResponse.builder()
                .assumptions(SimulationAssumptions.builder()
                        .annualClaimCount(ANNUAL_CLAIM_COUNT)
                        .annualExpectedVetCostKrw(ANNUAL_EXPECTED_VET_COST_KRW)
                        .assumptionSource(ASSUMPTION_SOURCE)
                        .build())
                .preExistingConditionWarning(hasPreExistingCondition ? PRE_EXISTING_CONDITION_WARNING : null)
                .insuranceAdvice(advice)
                .recommendedProducts(recommendedProducts)
                .build();
    }

    private Integer calculateAge(Object birthDateValue) {
        if (birthDateValue == null) {
            return null;
        }
        LocalDate birthDate = (birthDateValue instanceof java.sql.Date)
                ? ((java.sql.Date) birthDateValue).toLocalDate()
                : LocalDate.parse(birthDateValue.toString());
        return Period.between(birthDate, LocalDate.now()).getYears();
    }

    private boolean ageInRange(Map<String, Object> product, Integer age) {
        if (age == null) {
            return true;
        }
        Integer minAge = (Integer) product.get("join_age_min");
        Integer maxAge = (Integer) product.get("join_age_max");
        if (minAge == null || maxAge == null) {
            return true;
        }
        return age >= minAge && age <= maxAge;
    }

    private RecommendedProductResponse toRecommendedProduct(Map<String, Object> product) {
        BigDecimal monthlyPremium = (BigDecimal) product.get("premium_monthly_equiv");
        Integer reimbursementRatePct = (Integer) product.get("reimbursement_rate_pct");
        boolean breakEvenAvailable = reimbursementRatePct != null;

        return RecommendedProductResponse.builder()
                .productId(String.valueOf(((Number) product.get("product_id")).longValue()))
                .companyName((String) product.get("company_name"))
                .productName((String) product.get("product_name"))
                .monthlyPremiumKrw(monthlyPremium)
                .reimbursementStructure((String) product.get("reimbursement_structure"))
                .reimbursementRatePct(reimbursementRatePct)
                .reimbursementConfidence((String) product.get("reimbursement_confidence"))
                .regulatoryCapWarning((String) product.get("regulatory_cap_warning"))
                .breakEvenAvailable(breakEvenAvailable)
                .breakEvenScenarios(breakEvenAvailable ? buildScenarios(monthlyPremium, reimbursementRatePct) : null)
                .build();
    }

    private List<BreakEvenScenario> buildScenarios(BigDecimal monthlyPremium, int reimbursementRatePct) {
        BigDecimal annualPremium = monthlyPremium.multiply(BigDecimal.valueOf(12));
        BigDecimal annualReimbursement = BigDecimal.valueOf(ANNUAL_EXPECTED_VET_COST_KRW)
                .multiply(BigDecimal.valueOf(reimbursementRatePct))
                .divide(BigDecimal.valueOf(100));

        List<BreakEvenScenario> scenarios = new ArrayList<>();
        for (int years : BREAK_EVEN_YEARS) {
            BigDecimal cumulativePremium = annualPremium.multiply(BigDecimal.valueOf(years));
            BigDecimal expectedReimbursement = annualReimbursement.multiply(BigDecimal.valueOf(years));
            BigDecimal difference = expectedReimbursement.subtract(cumulativePremium);
            scenarios.add(BreakEvenScenario.builder()
                    .years(years)
                    .cumulativePremiumKrw(cumulativePremium.longValue())
                    .expectedReimbursementKrw(expectedReimbursement.longValue())
                    .isFavorable(difference.compareTo(BigDecimal.ZERO) > 0)
                    .differenceKrw(difference.longValue())
                    .build());
        }
        return scenarios;
    }

    private InsuranceAdvice buildAdvice(List<RecommendedProductResponse> products) {
        if (products.isEmpty()) {
            return InsuranceAdvice.builder()
                    .verdict("NEUTRAL")
                    .message("조건에 맞는 보험 상품을 찾지 못했어요. 나중에 다시 확인해주세요.")
                    .build();
        }

        long favorableCount = products.stream()
                .filter(RecommendedProductResponse::isBreakEvenAvailable)
                .filter(product -> product.getBreakEvenScenarios().stream()
                        .anyMatch(scenario -> scenario.getYears() == 5 && Boolean.TRUE.equals(scenario.getIsFavorable())))
                .count();

        String verdict;
        String message;
        if (favorableCount * 2 >= products.size()) {
            verdict = "FAVORABLE";
            message = "가입 시 보험료보다 예상 보장 혜택이 더 큰 상품이 많아요. 가입을 검토해보세요.";
        } else if (favorableCount == 0) {
            verdict = "UNFAVORABLE";
            message = "현재 조건에서는 예상 보장 혜택이 보험료보다 작은 경우가 많아요. 신중하게 검토해보세요.";
        } else {
            verdict = "NEUTRAL";
            message = "상품별로 유불리가 갈려요. 조건을 비교한 뒤 결정하세요.";
        }
        return InsuranceAdvice.builder().verdict(verdict).message(message).build();
    }

    private void persistSimulation(String petId, List<String> historyCodes, InsuranceAdvice advice) {
        String medicalHistoryCodesJson = historyCodes.stream()
                .map(code -> "\"" + code + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        Map<String, Object> simulation = new java.util.HashMap<>();
        simulation.put("petId", petId);
        simulation.put("medicalHistoryCodes", medicalHistoryCodesJson);
        simulation.put("verdict", advice.getVerdict());
        simulation.put("message", advice.getMessage());
        insuranceMapper.insertSimulation(simulation);
    }
}
