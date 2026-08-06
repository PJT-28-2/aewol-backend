package com.aewol.domain.insurance.dto.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import javax.validation.Constraint;
import javax.validation.Payload;

/** medicalHistoryCodes 배열 요소가 DENTAL/URINARY/FOREIGN_BODY/JOINT/SKIN/DIGESTIVE/NONE/OTHER 중 하나인지 검증한다 */
@Documented
@Constraint(validatedBy = AllowedMedicalHistoryCodeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedMedicalHistoryCode {
    String message() default "허용되지 않은 병력 코드입니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
