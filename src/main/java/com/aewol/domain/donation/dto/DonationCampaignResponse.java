package com.aewol.domain.donation.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DonationCampaignResponse {
    private final String id;
    private final String organizationId;
    private final String organization;
    private final String title;
    private final String category;
    private final int progress;
    private final BigDecimal raised;
    private final int participants;
    private final long daysLeft;
    private final boolean preferred;
}
