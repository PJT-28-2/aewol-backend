package com.aewol.domain.donation.dto;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class DonationSettingRequest {
    private boolean piggyBankEnabled;

    @NotNull(message = "저금 단위를 선택해 주세요.")
    @DecimalMin(value = "1", message = "저금 단위는 1원 이상이어야 합니다.")
    private BigDecimal savingUnit;

    private boolean autoDonate;
    private String campaignId;
}
