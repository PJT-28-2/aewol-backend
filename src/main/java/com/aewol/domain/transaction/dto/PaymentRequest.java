package com.aewol.domain.transaction.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Getter
@NoArgsConstructor
public class PaymentRequest {
    @NotBlank(message = "가맹점명을 입력해 주세요.")
    @Size(max = 100, message = "가맹점명은 100자 이하만 입력할 수 있습니다.")
    private String merchantName;

    @NotNull(message = "결제 금액을 입력해 주세요.")
    @DecimalMin(value = "1", message = "결제 금액은 1원 이상이어야 합니다.")
    @Digits(integer = 13, fraction = 0, message = "결제 금액은 1원 단위여야 합니다.")
    private BigDecimal amount;
    private String petId;
    private String memo;
}
