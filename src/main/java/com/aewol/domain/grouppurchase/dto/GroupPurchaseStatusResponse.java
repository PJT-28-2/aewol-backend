package com.aewol.domain.grouppurchase.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GroupPurchaseStatusResponse {
    private String memberId;
    private String productName;
    private String status;
    private Integer currentQuantity;
    private Integer targetQuantity;
    private LocalDateTime deadline;
    private BigDecimal unitPrice;
    private BigDecimal groupPrice;
    private GroupPurchaseStatusParticipantResponse participantInfo;
    private String noticeMessage;
}
