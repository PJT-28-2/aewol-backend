package com.aewol.domain.donation.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DonationOverviewResponse {
    private final BigDecimal balance;
    private final BigDecimal monthlySaved;
    private final String impactMessage;
    private final List<DonationCampaignResponse> campaigns;
    private final DonationSettingsResponse settings;
}
