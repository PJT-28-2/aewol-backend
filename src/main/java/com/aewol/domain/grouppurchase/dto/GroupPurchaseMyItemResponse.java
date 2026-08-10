package com.aewol.domain.grouppurchase.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GroupPurchaseMyItemResponse {
    private Long gpId;
    private Long memberId;
    private String productName;
    private String status;
    private Integer currentQuantity;
    private Integer targetQuantity;
    private LocalDateTime deadline;
    private LocalDateTime createdAt;
}
