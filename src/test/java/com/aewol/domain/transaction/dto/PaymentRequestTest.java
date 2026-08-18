package com.aewol.domain.transaction.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Set;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void should_acceptRequest_when_valuesAreValid() {
        assertTrue(validator.validate(request("애월동물병원", new BigDecimal("72000"))).isEmpty());
    }

    @Test
    void should_rejectRequest_when_amountIsNotPositive() {
        Set<ConstraintViolation<PaymentRequest>> violations =
                validator.validate(request("애월동물병원", BigDecimal.ZERO));

        assertEquals("결제 금액은 1원 이상이어야 합니다.", violations.iterator().next().getMessage());
    }

    @Test
    void should_rejectRequest_when_amountHasDecimalPlaces() {
        Set<ConstraintViolation<PaymentRequest>> violations =
                validator.validate(request("애월동물병원", new BigDecimal("72000.5")));

        assertEquals("결제 금액은 1원 단위여야 합니다.", violations.iterator().next().getMessage());
    }

    @Test
    void should_rejectRequest_when_amountExceedsDatabaseRange() {
        Set<ConstraintViolation<PaymentRequest>> violations =
                validator.validate(request("애월동물병원", new BigDecimal("10000000000000")));

        assertEquals("결제 금액은 1원 단위여야 합니다.", violations.iterator().next().getMessage());
    }

    @Test
    void should_rejectRequest_when_merchantNameExceedsMaximumLength() {
        Set<ConstraintViolation<PaymentRequest>> violations =
                validator.validate(request("가".repeat(101), BigDecimal.ONE));

        assertEquals("가맹점명은 100자 이하만 입력할 수 있습니다.",
                violations.iterator().next().getMessage());
    }

    private static PaymentRequest request(String merchantName, BigDecimal amount) {
        PaymentRequest request = new PaymentRequest();
        ReflectionTestUtils.setField(request, "merchantName", merchantName);
        ReflectionTestUtils.setField(request, "amount", amount);
        return request;
    }
}
