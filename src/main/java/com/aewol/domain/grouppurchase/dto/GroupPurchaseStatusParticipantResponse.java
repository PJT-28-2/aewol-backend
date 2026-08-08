package com.aewol.domain.grouppurchase.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GroupPurchaseStatusParticipantResponse {
    private Long participantId;
    private Integer purchaseQuantity;
    private BigDecimal paidAmount;
    private String paymentStatus;
    private LocalDateTime paidAt;
}
