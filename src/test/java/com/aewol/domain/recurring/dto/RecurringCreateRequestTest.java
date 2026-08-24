package com.aewol.domain.recurring.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Set;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RecurringCreateRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void should_acceptRequest_when_allValuesAreValid() {
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료", new BigDecimal("32000"), 15, "FOOD", "pet-1");

        assertEquals(0, validator.validate(request).size());
    }

    @Test
    void should_rejectRequest_when_priceIsNotPositive() {
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료", BigDecimal.ZERO, 15, "FOOD", "pet-1");

        Set<ConstraintViolation<RecurringCreateRequest>> violations = validator.validate(request);

        assertEquals("결제 금액은 1원 이상이어야 합니다.", violations.iterator().next().getMessage());
    }

    @Test
    void should_rejectRequest_when_cycleDayIsOutOfRange() {
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료", new BigDecimal("32000"), 32, "FOOD", "pet-1");

        Set<ConstraintViolation<RecurringCreateRequest>> violations = validator.validate(request);

        assertEquals("결제일은 1~31 사이여야 합니다.", violations.iterator().next().getMessage());
    }

    @Test
    void should_acceptRequest_when_cycleDayIsThirtyOne() {
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료", new BigDecimal("32000"), 31, "FOOD", "pet-1");

        assertEquals(0, validator.validate(request).size());
    }

    @Test
    void should_rejectRequest_when_priceHasDecimalPlaces() {
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료", new BigDecimal("32000.5"), 15, "FOOD", "pet-1");

        Set<ConstraintViolation<RecurringCreateRequest>> violations = validator.validate(request);

        assertEquals("결제 금액은 정수만 입력할 수 있습니다.",
                violations.iterator().next().getMessage());
    }

    @Test
    void should_rejectRequest_when_categoryIsUnsupported() {
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료", new BigDecimal("32000"), 15, "SOS", "pet-1");

        Set<ConstraintViolation<RecurringCreateRequest>> violations = validator.validate(request);

        assertEquals("지원하지 않는 카테고리입니다.", violations.iterator().next().getMessage());
    }

    @Test
    void should_rejectRequest_when_petIdIsBlank() {
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료", new BigDecimal("32000"), 15, "FOOD", "  ");

        Set<ConstraintViolation<RecurringCreateRequest>> violations = validator.validate(request);

        assertEquals("반려동물을 선택해 주세요.", violations.iterator().next().getMessage());
    }

    @Test
    void should_rejectRequest_when_idempotencyKeyExceedsMaxLength() {
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료", new BigDecimal("32000"), 15, "FOOD", "pet-1", "k".repeat(65));

        Set<ConstraintViolation<RecurringCreateRequest>> violations = validator.validate(request);

        assertEquals("중복 요청 방지 키는 64자 이하여야 합니다.",
                violations.iterator().next().getMessage());
    }
}
