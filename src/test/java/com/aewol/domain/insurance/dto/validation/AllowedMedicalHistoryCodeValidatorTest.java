package com.aewol.domain.insurance.dto.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AllowedMedicalHistoryCodeValidatorTest {

    private final AllowedMedicalHistoryCodeValidator validator = new AllowedMedicalHistoryCodeValidator();

    @Test
    @DisplayName("허용된 병력 코드는 유효하다고 판단한다")
    void should_returnTrue_whenCodeIsAllowed() {
        assertTrue(validator.isValid("JOINT", null));
        assertTrue(validator.isValid("NONE", null));
    }

    @Test
    @DisplayName("허용되지 않은 병력 코드는 유효하지 않다고 판단한다")
    void should_returnFalse_whenCodeIsNotAllowed() {
        assertFalse(validator.isValid("ALLERGY", null));
    }

    @Test
    @DisplayName("null이나 공백 문자열은 통과시킨다 (NotBlank가 별도로 처리)")
    void should_returnTrue_whenValueIsNullOrBlank() {
        assertTrue(validator.isValid(null, null));
        assertTrue(validator.isValid("  ", null));
    }
}
