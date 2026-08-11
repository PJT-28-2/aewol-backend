package com.aewol.domain.grouppurchase.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GroupPurchaseLeaveResponse {
    private String gpId;
    private Long participantId;
    private Integer currentQuantity;
    private Integer targetQuantity;
    private BigDecimal refundedAmount;
    private BigDecimal refundedWalletBalance;
    private String paymentStatus;
    private LocalDateTime canceledAt;
}
