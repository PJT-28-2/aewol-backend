package com.aewol.domain.insurance.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link InsuranceProductPolicy}의 나이 적격성 술어를 단독으로 검증한다.
 * 계획서 v3.1 Expanded Test Plan / worker-4 지시서 5절 (worker-1이 남긴 갭).
 */
class InsuranceProductPolicyTest {

    private Map<String, Object> product(String ageBasis, Integer minAge, Integer maxAge) {
        Map<String, Object> product = new HashMap<>();
        product.put("age_basis", ageBasis);
        product.put("join_age_min", minAge);
        product.put("join_age_max", maxAge);
        return product;
    }

    @Test
    @DisplayName("age_basis=OWNER면 반려동물 나이가 가입연령 범위 밖이어도 배제되지 않는다")
    void should_notExclude_whenAgeBasisIsOwnerAndPetAgeOutsideRange() {
        Map<String, Object> product = product("OWNER", 18, 80);

        assertTrue(InsuranceProductPolicy.isEligibleByAge(product, 3));
    }

    @Test
    @DisplayName("age_basis=PET이고 반려동물 나이가 가입연령 범위 밖이면 배제된다")
    void should_exclude_whenAgeBasisIsPetAndPetAgeOutsideRange() {
        Map<String, Object> product = product("PET", 5, 10);

        assertFalse(InsuranceProductPolicy.isEligibleByAge(product, 3));
    }

    @Test
    @DisplayName("age_basis=PET이고 반려동물 나이가 가입연령 범위 안이면 적격이다")
    void should_beEligible_whenAgeBasisIsPetAndPetAgeWithinRange() {
        Map<String, Object> product = product("PET", 0, 10);

        assertTrue(InsuranceProductPolicy.isEligibleByAge(product, 3));
    }

    @Test
    @DisplayName("age_basis가 null이면 PET로 취급해 범위 밖이면 배제한다")
    void should_treatNullAgeBasisAsPet() {
        Map<String, Object> product = product(null, 5, 10);

        assertFalse(InsuranceProductPolicy.isEligibleByAge(product, 3));
    }

    @Test
    @DisplayName("반려동물 나이가 null이면 항상 적격으로 본다")
    void should_beEligible_whenPetAgeIsNull() {
        Map<String, Object> product = product("PET", 5, 10);

        assertTrue(InsuranceProductPolicy.isEligibleByAge(product, null));
    }

    @Test
    @DisplayName("가입연령 정보가 없으면 age_basis=PET이어도 항상 적격으로 본다")
    void should_beEligible_whenJoinAgeRangeMissing() {
        Map<String, Object> product = product("PET", null, null);

        assertTrue(InsuranceProductPolicy.isEligibleByAge(product, 3));
    }
}
