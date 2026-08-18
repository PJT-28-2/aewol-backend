package com.aewol.domain.transaction.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Getter
@NoArgsConstructor
public class PaymentRequest {
    public static final int MAX_MERCHANT_NAME_LENGTH = 100;
    public static final String MIN_AMOUNT_VALUE = "1";
    public static final String MAX_AMOUNT_VALUE = "9999999999999";
    public static final BigDecimal MIN_AMOUNT = new BigDecimal(MIN_AMOUNT_VALUE);
    public static final BigDecimal MAX_AMOUNT = new BigDecimal(MAX_AMOUNT_VALUE);
    public static final String MERCHANT_NAME_REQUIRED_MESSAGE = "가맹점명을 입력해 주세요.";
    public static final String MERCHANT_NAME_LENGTH_MESSAGE = "가맹점명은 100자 이하만 입력할 수 있습니다.";
    public static final String AMOUNT_REQUIRED_MESSAGE = "결제 금액을 입력해 주세요.";
    public static final String AMOUNT_MIN_MESSAGE = "결제 금액은 1원 이상이어야 합니다.";
    public static final String AMOUNT_MAX_MESSAGE = "결제 금액은 정수부 13자리 이하만 입력할 수 있습니다.";
    public static final String AMOUNT_INTEGER_MESSAGE = "결제 금액은 1원 단위여야 합니다.";

    @NotBlank(message = MERCHANT_NAME_REQUIRED_MESSAGE)
    @Size(max = MAX_MERCHANT_NAME_LENGTH, message = MERCHANT_NAME_LENGTH_MESSAGE)
    private String merchantName;

    @NotNull(message = AMOUNT_REQUIRED_MESSAGE)
    @DecimalMin(value = MIN_AMOUNT_VALUE, message = AMOUNT_MIN_MESSAGE)
    @DecimalMax(value = MAX_AMOUNT_VALUE, message = AMOUNT_MAX_MESSAGE)
    private BigDecimal amount;
    private String petId;
    private String memo;

    @AssertTrue(message = AMOUNT_INTEGER_MESSAGE)
    public boolean isAmountWholeNumber() {
        return amount == null || amount.stripTrailingZeros().scale() <= 0;
    }
}
