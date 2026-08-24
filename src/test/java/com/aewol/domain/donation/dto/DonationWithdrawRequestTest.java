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

class DonationWithdrawRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void should_rejectRequest_when_amountHasThreeDecimalPlaces() {
        DonationWithdrawRequest request = request("1.001", "withdraw-1");

        Set<ConstraintViolation<DonationWithdrawRequest>> violations = validator.validate(request);

        assertEquals("출금 금액은 소수점 둘째 자리까지, 정수 13자리 이하여야 합니다.",
                violations.iterator().next().getMessage());
    }

    @Test
    void should_rejectRequest_when_amountExceedsIntegerDigits() {
        DonationWithdrawRequest request = request("10000000000000.00", "withdraw-1");

        Set<ConstraintViolation<DonationWithdrawRequest>> violations = validator.validate(request);

        assertEquals("출금 금액은 소수점 둘째 자리까지, 정수 13자리 이하여야 합니다.",
                violations.iterator().next().getMessage());
    }

    private static DonationWithdrawRequest request(String amount, String key) {
        DonationWithdrawRequest request = new DonationWithdrawRequest();
        ReflectionTestUtils.setField(request, "amount", new BigDecimal(amount));
        ReflectionTestUtils.setField(request, "idempotencyKey", key);
        return request;
    }
}
