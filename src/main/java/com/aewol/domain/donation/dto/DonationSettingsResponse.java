package com.aewol.domain.donation.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DonationSettingsResponse {
    private final boolean piggyBankEnabled;
    private final BigDecimal savingUnit;
    private final boolean autoDonate;
    private final String autoDonateCampaignId;
}
