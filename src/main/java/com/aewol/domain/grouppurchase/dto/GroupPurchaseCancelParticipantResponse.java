package com.aewol.domain.grouppurchase.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GroupPurchaseCancelParticipantResponse {
    private Long participantId;
    private String memberId;
    private BigDecimal refundedAmount;
    private BigDecimal refundedWalletBalance;
    private String paymentStatus;
}
