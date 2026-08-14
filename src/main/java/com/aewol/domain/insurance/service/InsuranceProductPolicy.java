package com.aewol.domain.insurance.service;

import java.util.Map;

/**
 * 나이 적격성 판정을 {@link InsuranceSimulationServiceImpl}과 {@link InsuranceProductServiceImpl}이
 * 공용으로 사용하기 위한 술어 모음이다 (계획서 Decision 4 / S6, Critic C-1 참조).
 *
 * <p>이 클래스는 "판정"만 제공한다. 판정 결과를 하드 필터(배제)로 쓸지, 정렬 강등(soft demote)으로
 * 쓸지는 각 서비스가 결정한다 — {@code InsuranceSimulationServiceImpl}은 하드 필터로,
 * {@code InsuranceProductServiceImpl}은 정렬 강등 + CONFIRMED 하드 필터로 사용한다.</p>
 */
public final class InsuranceProductPolicy {

    private InsuranceProductPolicy() {
    }

    /**
     * 상품이 주어진 반려동물 나이에 적격한지 판정한다.
     *
     * <p>{@code age_basis}가 {@code 'PET'}일 때만 반려동물 나이로 판정하고, {@code 'OWNER'}면
     * 가입 연령이 보호자 기준이므로 반려동물 나이와 무관하게 항상 적격으로 본다.
     * {@code age_basis}가 null이면 보수적으로 {@code 'PET'}로 취급한다 — 기존 단위테스트
     * 헬퍼가 이 키를 넣지 않으므로, 이렇게 해야 기존 테스트가 그대로 통과한다.</p>
     *
     * @param product 상품 원본 데이터(Mapper의 {@code SELECT *} 결과 Map)
     * @param petAge  반려동물 나이. null이면 나이 정보가 없다는 뜻이므로 항상 적격으로 본다.
     */
    public static boolean isEligibleByAge(Map<String, Object> product, Integer petAge) {
        if (petAge == null) {
            return true;
        }
        String ageBasis = (String) product.get("age_basis");
        if ("OWNER".equals(ageBasis)) {
            return true;
        }
        Integer minAge = (Integer) product.get("join_age_min");
        Integer maxAge = (Integer) product.get("join_age_max");
        if (minAge == null || maxAge == null) {
            return true;
        }
        return petAge >= minAge && petAge <= maxAge;
    }
}
