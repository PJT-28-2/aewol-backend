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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InsuranceSimulationServiceImpl implements InsuranceSimulationService {

    // TODO(S2): 출처 없는 상수. worker-2가 연 진료 횟수(n)의 출처를 확정하기 전까지는
    // Decision 5 분기표의 "미확보" 행만 적용한다 (CLAIM_COUNT_SOURCE_CONFIRMED=false).
    // 출처가 확정되면 이 값과 CLAIM_COUNT_SOURCE_CONFIRMED를 함께 갱신할 것.
    private static final int ANNUAL_CLAIM_COUNT = 6;
    private static final boolean CLAIM_COUNT_SOURCE_CONFIRMED = false;

    // TODO(S2): worker-2 리서치 결과의 마리당 통계치로 교체. 현재 값은 가구 평균
    // 연환산 추정치라 마리당 수치로는 부적절함이 확인된 상태(계획서 S2 참조).
    private static final long ANNUAL_EXPECTED_VET_COST_KRW = 513_500L;
    private static final String ASSUMPTION_SOURCE =
            "KB금융경영연구소 2025 한국 반려동물 보고서(가구 평균 2년 102.7만원 기준 연환산 추정치)";
    /**
     * 사용자가 {@code annualMedicalCostKrw}를 직접 넘긴 경우의 근거 문구.
     *
     * <p>이 경우 계산에 쓰인 값은 통계치가 아니라 사용자 입력값이므로 위 통계 출처를
     * 그대로 붙이면 "이 숫자는 KB 리포트 기준"이라는 거짓 표시가 된다(응답과
     * insurance_simulation 이력 양쪽 모두). 확인 안 된 걸 확인된 것처럼 말하지
     * 않는다 — 계획서 Principle 1.</p>
     */
    private static final String USER_ADJUSTED_ASSUMPTION_SOURCE = "사용자가 직접 입력한 예상 연 의료비";
    private static final String PRE_EXISTING_CONDITION_WARNING =
            "기존 병력이 있는 경우 일부 보장이 제한되거나 면책될 수 있습니다. 가입 전 약관을 확인하세요.";
    private static final int[] BREAK_EVEN_YEARS = {1, 5, 10};
    private static final int RECOMMENDED_PRODUCT_LIMIT = 5;
    private static final String MEDICAL_CATEGORY = "MEDICAL";

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

        boolean isUserAdjusted = request.getAnnualMedicalCostKrw() != null;
        long annualMedicalCostKrw = isUserAdjusted
                ? request.getAnnualMedicalCostKrw()
                : ANNUAL_EXPECTED_VET_COST_KRW;

        // 환급률/자기부담금/연간한도/출처/신뢰도의 진실 원천은 insurance_product가
        // 아니라 insurance_product_plan_tiers의 견적 기준 티어(is_reference_tier=1)다
        // (V30 재설계 — 국내 펫보험의 보장비율은 상품 고정 속성이 아니라 가입 시
        // 선택하는 플랜 옵션이라는 리서치 발견에 따른 것). 후보를 먼저 나이/카테고리로
        // 거른 뒤, 그 상품들의 견적 기준 티어만 별도로 조회해 병합한다.
        List<Map<String, Object>> candidateProducts = insuranceMapper.findProductsBySpecies(species).stream()
                .filter(product -> InsuranceProductPolicy.isEligibleByAge(product, age))
                .filter(product -> MEDICAL_CATEGORY.equals(product.get("product_category")))
                .collect(Collectors.toList());

        List<Long> candidateProductIds = candidateProducts.stream()
                .map(product -> ((Number) product.get("product_id")).longValue())
                .collect(Collectors.toList());
        // 병합 함수 (existing, duplicate) -> existing은 방어 코드다. "상품당 견적 기준
        // 티어는 1개"는 V33의 유니크 제약으로 강제되지만, 제약이 없던 시점에 만들어진
        // DB나 제약을 우회하는 경로가 있으면 병합 함수 없는 toMap은 IllegalStateException을
        // 던져 해당 종(species)의 시뮬레이션 요청 전체가 500으로 죽는다. 데이터 무결성
        // 문제 하나가 API 전체를 내리지 않도록, 조회 순서(tier_id 오름차순)의 첫 행을
        // 결정적으로 고른다 — InsuranceProductServiceImpl의 findFirst()와 같은 행이다.
        Map<Long, Map<String, Object>> referenceTierByProductId = candidateProductIds.isEmpty()
                ? Collections.emptyMap()
                : insuranceMapper.findReferenceTiersByProductIds(candidateProductIds).stream()
                        .collect(Collectors.toMap(
                                tier -> ((Number) tier.get("product_id")).longValue(),
                                tier -> tier,
                                (existing, duplicate) -> existing));

        // Decision 4(A): UNVERIFIED 필터를 제거해 근거 미확인 상품(=견적 기준 티어가
        // 없거나 rate가 NULL인 상품)도 후보에 남긴다. breakEvenAvailable=true
        // (=견적 기준 티어의 reimbursement_rate_pct 확정)인 상품을 우선 정렬한다.
        List<RecommendedProductResponse> recommendedProducts = candidateProducts.stream()
                .sorted(Comparator
                        .comparing((Map<String, Object> product) -> referenceTierRate(product, referenceTierByProductId) == null)
                        .thenComparing(product -> (BigDecimal) product.get("premium_monthly_equiv")))
                .limit(RECOMMENDED_PRODUCT_LIMIT)
                .map(product -> toRecommendedProduct(product,
                        referenceTierByProductId.get(((Number) product.get("product_id")).longValue()),
                        annualMedicalCostKrw))
                .collect(Collectors.toList());

        List<String> historyCodes = request.getMedicalHistoryCodes() == null
                ? List.of() : request.getMedicalHistoryCodes();
        boolean hasPreExistingCondition = historyCodes.stream().anyMatch(code -> !"NONE".equals(code));

        InsuranceAdvice advice = buildAdvice(recommendedProducts);

        // 대표 상품 = breakEvenAvailable=true인 추천 상품 중 보험료 최저가.
        // 정렬 1위가 아니라 "계산 가능한 것 중 최저가"다 — Decision 4-A 이후
        // 정렬 1위가 미확인 상품일 수 있기 때문이다 (계획서 M-8).
        Optional<RecommendedProductResponse> representativeProduct = recommendedProducts.stream()
                .filter(RecommendedProductResponse::isBreakEvenAvailable)
                .min(Comparator.comparing(RecommendedProductResponse::getMonthlyPremiumKrw));

        persistSimulation(request.getPetId(), historyCodes, advice, annualMedicalCostKrw,
                isUserAdjusted, representativeProduct.orElse(null));

        return SimulationResponse.builder()
                .assumptions(SimulationAssumptions.builder()
                        .annualExpectedVetCostKrw(annualMedicalCostKrw)
                        .assumptionSource(assumptionSource(isUserAdjusted))
                        .isUserAdjusted(isUserAdjusted)
                        .build())
                .preExistingConditionWarning(hasPreExistingCondition ? PRE_EXISTING_CONDITION_WARNING : null)
                .insuranceAdvice(advice)
                .recommendedProducts(recommendedProducts)
                .build();
    }

    /** 응답과 이력에 동일한 근거 문구가 남도록 한 곳에서 판정한다. */
    private String assumptionSource(boolean isUserAdjusted) {
        return isUserAdjusted ? USER_ADJUSTED_ASSUMPTION_SOURCE : ASSUMPTION_SOURCE;
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

    /** 후보 상품의 견적 기준 티어 환급률만 뽑아본다(정렬용). 티어가 없으면 null(=미확인). */
    private Integer referenceTierRate(Map<String, Object> product, Map<Long, Map<String, Object>> referenceTierByProductId) {
        Map<String, Object> tier = referenceTierByProductId.get(((Number) product.get("product_id")).longValue());
        return tier == null ? null : (Integer) tier.get("reimbursement_rate_pct");
    }

    private RecommendedProductResponse toRecommendedProduct(Map<String, Object> product,
            Map<String, Object> referenceTier, long annualMedicalCostKrw) {
        BigDecimal monthlyPremium = (BigDecimal) product.get("premium_monthly_equiv");
        Integer reimbursementRatePct = referenceTier == null ? null : (Integer) referenceTier.get("reimbursement_rate_pct");
        boolean breakEvenAvailable = reimbursementRatePct != null;

        return RecommendedProductResponse.builder()
                .productId(String.valueOf(((Number) product.get("product_id")).longValue()))
                .companyName((String) product.get("company_name"))
                .productName((String) product.get("product_name"))
                .monthlyPremiumKrw(monthlyPremium)
                .reimbursementStructure((String) product.get("reimbursement_structure"))
                .reimbursementRatePct(reimbursementRatePct)
                .reimbursementConfidence(referenceTier == null ? null : (String) referenceTier.get("reimbursement_confidence"))
                .reimbursementRateNote((String) product.get("reimbursement_rate_note"))
                .regulatoryCapWarning((String) product.get("regulatory_cap_warning"))
                .deductibleKrw(referenceTier == null ? null : (BigDecimal) referenceTier.get("deductible_krw"))
                .deductibleBasis(referenceTier == null ? null : (String) referenceTier.get("deductible_basis"))
                .deductibleApplied(isDeductibleApplied(product, referenceTier))
                .annualLimitKrw(referenceTier == null ? null : (BigDecimal) referenceTier.get("annual_limit_krw"))
                .reimbursementSourceUrl(referenceTier == null ? null : (String) referenceTier.get("reimbursement_source_url"))
                .breakEvenAvailable(breakEvenAvailable)
                .breakEvenScenarios(breakEvenAvailable
                        ? buildScenarios(monthlyPremium, reimbursementRatePct, product, referenceTier, annualMedicalCostKrw)
                        : null)
                .build();
    }

    private List<BreakEvenScenario> buildScenarios(BigDecimal monthlyPremium, int reimbursementRatePct,
            Map<String, Object> product, Map<String, Object> referenceTier, long annualMedicalCostKrw) {
        BigDecimal annualPremium = monthlyPremium.multiply(BigDecimal.valueOf(12));
        BigDecimal annualReimbursement = calculateAnnualReimbursement(
                BigDecimal.valueOf(annualMedicalCostKrw), reimbursementRatePct, product, referenceTier);

        List<BreakEvenScenario> scenarios = new ArrayList<>();
        for (int years : BREAK_EVEN_YEARS) {
            BigDecimal cumulativePremium = annualPremium.multiply(BigDecimal.valueOf(years));
            // 연 단위로 계산한 값을 그대로 연차만큼 곱한다(집계 레벨 계산).
            // 건별로 max(0, cᵢ-d)를 합산하는 방식(Σmax(0, cᵢ−d))보다 이 방식이
            // 항상 작거나 같은 보수적 방향으로 틀린다 — 자기부담금을 매 방문마다
            // 반영하지 않고 연 1회로 뭉뚱그려 계산하기 때문이다. 화면에도 이 한계를 고지한다.
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

    /**
     * 계산에 실제로 반영할 연 단위 자기부담금 차감액을 구한다(계획서 Decision 5의
     * 자기부담금 분기표). 반영하지 않으면 null.
     *
     * <p>이 판정을 {@link #isDeductibleApplied}와 {@link #calculateAnnualReimbursement}가
     * 공유한다 — 두 곳에 조건을 따로 쓰면 "화면에는 반영됐다고 고지하는데 계산에는
     * 안 들어간" 상태가 조용히 생긴다(Principle 2).</p>
     *
     * <p>자기부담금을 반영하려면 금액·기준·순서 세 값이 <b>모두</b> 확정돼야 한다.
     * 하나라도 미확인이면 어느 쪽으로도 가정하지 않고 반영하지 않으며, 대신
     * {@code deductibleApplied=false}로 "반영하지 않았다"는 사실 자체를 화면에
     * 고지한다 — 계획서 Principle 1.</p>
     *
     * <p>{@code n}(연 진료 횟수) 출처가 아직 미확정({@link #CLAIM_COUNT_SOURCE_CONFIRMED}=false)이라
     * 현재 실질적으로 동작하는 분기는 다음과 같다:</p>
     * <ul>
     *   <li>{@code deductible_krw}가 NULL → 미반영, {@code C × rate/100} 그대로 적용
     *       (현재 시드 23개 티어 중 21개가 이 분기)</li>
     *   <li>{@code deductible_basis} 또는 {@code deductible_order}가 미확인 → 미반영
     *       (현재 시드에서 금액이 있는 메리츠 2건이 이 분기 — 둘 다 basis·order 모두 NULL)</li>
     *   <li>{@code deductible_basis='PER_VISIT'} &amp; n 미확보 → 해당 상품만 미반영</li>
     *   <li>세 값이 모두 확정 → {@code PER_VISIT}이면 {@code d×n}, {@code PER_YEAR}면 {@code d}</li>
     * </ul>
     * <p>{@code n} 출처가 확정되면 {@link #CLAIM_COUNT_SOURCE_CONFIRMED}를 true로 바꿔
     * PER_VISIT 확보 분기가 활성화되도록 한다. TODO(S2).</p>
     *
     * <p>{@code min(annualLimit, ·)} 상한은 의도적으로 넣지 않는다 — 시드 담보 한도
     * (1,000만~4,000만원) 대비 연 의료비가 낮아 도달 불가능한 죽은 분기이기 때문이다
     * (계획서 Principle 4). {@code annualLimitKrw}는 표시 전용으로만 노출한다.</p>
     *
     * <p>자기부담금 금액({@code deductible_krw})·기준({@code deductible_basis})은
     * 가입 시 선택하는 플랜 옵션이라 견적 기준 티어({@code referenceTier})에서 읽는다.
     * 적용 순서({@code deductible_order})는 약관상 계산 규칙(플랜 무관)이라 상품
     * 레벨({@code product})에 그대로 남아 있다 (V30 재설계).</p>
     */
    private BigDecimal effectiveAnnualDeductible(Map<String, Object> product, Map<String, Object> referenceTier) {
        if (referenceTier == null) {
            return null;
        }
        BigDecimal deductible = (BigDecimal) referenceTier.get("deductible_krw");
        if (deductible == null) {
            // d IS NULL 행: 자기부담금 미반영
            return null;
        }

        String basis = (String) referenceTier.get("deductible_basis");
        if (basis == null) {
            // 기준(건당/연간) 미확인 행: PER_YEAR로 가정하지 않는다. 실제 약관이
            // 건당(PER_VISIT)이면 차감액이 n배 작아져 보장금이 과대 계산된다
            // (메리츠 자부담 3만원 기준: 연 1회 차감 33,850원 과대).
            return null;
        }
        if ("PER_VISIT".equals(basis) && !CLAIM_COUNT_SOURCE_CONFIRMED) {
            // n 미확보 + PER_VISIT 행: 해당 상품만 자기부담금 미반영
            return null;
        }

        // 적용 순서(deductible_order) 미확인 행도 같은 이유로 반영하지 않는다.
        // 선차감 BEFORE_RATE = (C−d)×rate = C×rate − d×rate 이고
        // 후차감 AFTER_RATE  = C×rate − d 인데, rate ≤ 1이므로 d×rate ≤ d,
        // 즉 BEFORE_RATE ≥ AFTER_RATE가 항상 성립한다. 순서를 모를 때 BEFORE_RATE로
        // 떨어뜨리면 둘 중 보장금이 큰 쪽(=가입자에게 유리해 보이는 쪽)을 말없이
        // 고르는 셈이라, 바로 위 basis 가드와 같은 원칙을 어긴다.
        // 현재 시드는 24개 상품 전부 deductible_order가 NULL이므로, 이 가드가 없으면
        // 누군가 basis만 채우는 순간 전 상품이 곧바로 과대 계산으로 넘어간다.
        String order = (String) product.get("deductible_order");
        if (!"BEFORE_RATE".equals(order) && !"AFTER_RATE".equals(order)) {
            return null;
        }

        return "PER_VISIT".equals(basis)
                ? deductible.multiply(BigDecimal.valueOf(ANNUAL_CLAIM_COUNT))
                : deductible;
    }

    /**
     * 자기부담금이 실제로 손익분기 계산에 반영됐는지 알려준다.
     *
     * <p>금액이 채워져 있어도 기준·순서를 모르면 반영하지 않으므로, 금액 유무만으로는
     * 화면이 "자기부담금 미반영" 고지를 정확히 낼 수 없다. 그 판정을 서버가 단일
     * 원천으로 내려준다 — 프론트가 조건을 재구성하면 산식이 두 곳에 생긴다.</p>
     */
    private Boolean isDeductibleApplied(Map<String, Object> product, Map<String, Object> referenceTier) {
        return effectiveAnnualDeductible(product, referenceTier) != null;
    }

    private BigDecimal calculateAnnualReimbursement(BigDecimal annualCost, int reimbursementRatePct,
            Map<String, Object> product, Map<String, Object> referenceTier) {
        BigDecimal rate = BigDecimal.valueOf(reimbursementRatePct).divide(BigDecimal.valueOf(100));
        BigDecimal deductionAmount = effectiveAnnualDeductible(product, referenceTier);

        if (deductionAmount == null) {
            return annualCost.multiply(rate);
        }
        if ("AFTER_RATE".equals(product.get("deductible_order"))) {
            // 보장률 선적용: C×rate/100에서 자기부담금을 그대로 차감
            BigDecimal grossReimbursement = annualCost.multiply(rate);
            return grossReimbursement.subtract(deductionAmount).max(BigDecimal.ZERO);
        }
        // BEFORE_RATE(선차감): max(0, C-d[×n]) × rate/100
        // 여기 도달했다는 건 order가 BEFORE_RATE로 명시됐다는 뜻이다 —
        // 미확인 행은 위에서 deductionAmount=null로 이미 걸러진다.
        BigDecimal netCost = annualCost.subtract(deductionAmount).max(BigDecimal.ZERO);
        return netCost.multiply(rate);
    }

    private InsuranceAdvice buildAdvice(List<RecommendedProductResponse> products) {
        if (products.isEmpty()) {
            return InsuranceAdvice.builder()
                    .verdict("NEUTRAL")
                    .message("조건에 맞는 보험 상품을 찾지 못했어요. 나중에 다시 확인해주세요.")
                    .build();
        }

        // 손익분기를 계산할 수 있는(breakEvenAvailable=true) 상품만 판정 대상으로 삼는다.
        // 계산 불가 상품까지 분모에 섞으면 "비교를 한 건도 안 했는데 불리하다"는 잘못된 판정이 나온다.
        List<RecommendedProductResponse> calculableProducts = products.stream()
                .filter(RecommendedProductResponse::isBreakEvenAvailable)
                .collect(Collectors.toList());

        if (calculableProducts.isEmpty()) {
            return InsuranceAdvice.builder()
                    .verdict("NEUTRAL")
                    .message("추천 상품의 보장비율 정보가 아직 확인되지 않아 손익분기를 비교하지 못했어요. 상품 상세를 직접 확인해보세요.")
                    .build();
        }

        long favorableCount = calculableProducts.stream()
                .filter(product -> product.getBreakEvenScenarios().stream()
                        .anyMatch(scenario -> scenario.getYears() == 5 && Boolean.TRUE.equals(scenario.getIsFavorable())))
                .count();

        String verdict;
        String message;
        if (favorableCount * 2 > calculableProducts.size()) {
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private void persistSimulation(String petId, List<String> historyCodes, InsuranceAdvice advice,
            long annualMedicalCostKrw, boolean isUserAdjusted, RecommendedProductResponse representativeProduct) {
        Map<String, Object> simulation = new java.util.HashMap<>();
        simulation.put("petId", petId);
        simulation.put("medicalHistoryCodes", toJson(historyCodes));
        simulation.put("verdict", advice.getVerdict());
        simulation.put("message", advice.getMessage());
        simulation.put("assumedAnnualMedicalCostKrw", annualMedicalCostKrw);
        simulation.put("assumptionSource", assumptionSource(isUserAdjusted));
        simulation.put("representativeProductId",
                representativeProduct == null ? null : Long.valueOf(representativeProduct.getProductId()));
        simulation.put("representativeProductLabel",
                representativeProduct == null
                        ? null
                        : representativeProduct.getCompanyName() + " " + representativeProduct.getProductName());
        simulation.put("isUserAdjusted", isUserAdjusted);
        insuranceMapper.insertSimulation(simulation);
    }

    private String toJson(List<String> historyCodes) {
        try {
            return OBJECT_MAPPER.writeValueAsString(historyCodes);
        } catch (JsonProcessingException e) {
            throw new BusinessException("병력 코드 저장 형식 변환에 실패했습니다.");
        }
    }
}
