package com.aewol.domain.donation.dto;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class DonationRequest {

    @NotNull(message = "기부 금액을 입력해 주세요.")
    @DecimalMin(value = "1", message = "기부 금액은 1원 이상이어야 합니다.")
    private BigDecimal amount;

    @NotBlank(message = "기부 캠페인을 선택해 주세요.")
    private String campaignId;

    private String idempotencyKey;
}
