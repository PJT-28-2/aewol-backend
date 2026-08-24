package com.aewol.domain.donation.dto;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class DonationWithdrawRequest {

    @NotBlank(message = "중복 요청 방지 키를 입력해 주세요.")
    @Size(max = 64, message = "중복 요청 방지 키는 64자 이하여야 합니다.")
    private String idempotencyKey;

    @NotNull(message = "출금 금액을 입력해 주세요.")
    @DecimalMin(value = "1", message = "출금 금액은 1원 이상이어야 합니다.")
    @Digits(integer = 13, fraction = 2, message = "출금 금액은 소수점 둘째 자리까지, 정수 13자리 이하여야 합니다.")
    private BigDecimal amount;
}
