package com.aewol.domain.donation.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DonationPreferenceResponse {
    private final String organizationId;
    private final boolean preferred;
}
