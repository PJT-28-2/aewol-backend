package com.aewol.domain.grouppurchase.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GroupPurchaseJoinResponse {
    private String gpId;
    private Long participantId;
    private Integer quantity;
    private Integer currentQuantity;
    private Integer targetQuantity;
    private String recipientName;
    private String recipientPhone;
    private String zipCode;
    private String address;
    private String addressDetail;
    private String paymentStatus;
    private BigDecimal paidAmount;
    private LocalDateTime paidAt;
    private LocalDateTime joinedAt;
}
