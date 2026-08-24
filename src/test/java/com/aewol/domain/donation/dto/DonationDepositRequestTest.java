package com.aewol.domain.donation.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Set;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DonationDepositRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void should_rejectRequest_when_amountHasThreeDecimalPlaces() {
        DonationDepositRequest request = request("1.001", "deposit-1");

        Set<ConstraintViolation<DonationDepositRequest>> violations = validator.validate(request);

        assertEquals("넣을 금액은 소수점 둘째 자리까지, 정수 13자리 이하여야 합니다.",
                violations.iterator().next().getMessage());
    }

    @Test
    void should_rejectRequest_when_amountExceedsIntegerDigits() {
        DonationDepositRequest request = request("10000000000000.00", "deposit-1");

        Set<ConstraintViolation<DonationDepositRequest>> violations = validator.validate(request);

        assertEquals("넣을 금액은 소수점 둘째 자리까지, 정수 13자리 이하여야 합니다.",
                violations.iterator().next().getMessage());
    }

    @Test
    void should_acceptRequest_when_amountHasTwoDecimalPlaces() {
        DonationDepositRequest request = request("2000.50", "deposit-1");

        assertEquals(0, validator.validate(request).size());
    }

    private static DonationDepositRequest request(String amount, String key) {
        DonationDepositRequest request = new DonationDepositRequest();
        ReflectionTestUtils.setField(request, "amount", new BigDecimal(amount));
        ReflectionTestUtils.setField(request, "idempotencyKey", key);
        return request;
    }
}
