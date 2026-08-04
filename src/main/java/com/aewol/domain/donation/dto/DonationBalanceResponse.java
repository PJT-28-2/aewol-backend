package com.aewol.domain.donation.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DonationBalanceResponse {
    private final String donationId;
    private final BigDecimal balance;
}
