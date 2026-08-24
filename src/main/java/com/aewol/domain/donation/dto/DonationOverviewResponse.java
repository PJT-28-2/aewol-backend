package com.aewol.domain.donation.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DonationOverviewResponse {
    private final BigDecimal balance;
    /** 애월지갑(MAIN) 잔액. 저금통에 직접 넣을 수 있는 한도. */
    private final BigDecimal walletBalance;
    private final BigDecimal monthlySaved;
    private final String impactMessage;
    private final List<DonationCampaignResponse> campaigns;
    private final DonationSettingsResponse settings;
}
