package com.aewol.domain.insurance.dto.validation;

import java.util.Set;
import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class AllowedMedicalHistoryCodeValidator implements ConstraintValidator<AllowedMedicalHistoryCode, String> {

    private static final Set<String> ALLOWED_CODES = Set.of(
            "DENTAL", "URINARY", "FOREIGN_BODY", "JOINT", "SKIN", "DIGESTIVE", "NONE", "OTHER");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null/공백은 @NotBlank가 별도로 처리하므로 여기서는 통과시킨다
        if (value == null || value.isBlank()) {
            return true;
        }
        return ALLOWED_CODES.contains(value);
    }
}
