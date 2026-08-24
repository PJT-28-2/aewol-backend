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
public class DonationRequest {

    @NotNull(message = "기부 금액을 입력해 주세요.")
    @DecimalMin(value = "1", message = "기부 금액은 1원 이상이어야 합니다.")
    private BigDecimal amount;

    @NotBlank(message = "기부 캠페인을 선택해 주세요.")
    private String campaignId;

    @NotBlank(message = "중복 요청 방지 키를 입력해 주세요.")
    @Size(max = 64, message = "중복 요청 방지 키는 64자 이하여야 합니다.")
    private String idempotencyKey;
}
