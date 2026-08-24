package com.aewol.domain.donation.dto;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class DonationDepositRequest {

    @NotBlank(message = "중복 요청 방지 키를 입력해 주세요.")
    @Size(max = 64, message = "중복 요청 방지 키는 64자 이하여야 합니다.")
    private String idempotencyKey;

    @NotNull(message = "넣을 금액을 입력해 주세요.")
    @DecimalMin(value = "1", message = "넣을 금액은 1원 이상이어야 합니다.")
    private BigDecimal amount;
}
