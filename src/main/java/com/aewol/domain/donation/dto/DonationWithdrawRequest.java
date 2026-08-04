package com.aewol.domain.donation.dto;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class DonationWithdrawRequest {

    @NotNull(message = "출금 금액을 입력해 주세요.")
    @DecimalMin(value = "1", message = "출금 금액은 1원 이상이어야 합니다.")
    private BigDecimal amount;
}
