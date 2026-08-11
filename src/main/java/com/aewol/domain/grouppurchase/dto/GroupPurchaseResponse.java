package com.aewol.domain.grouppurchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GroupPurchaseResponse {
    private String gpId;
    private String memberId;
    private String productName;
    private String category;
    private String image;
    private BigDecimal unitPrice;
    private BigDecimal groupPrice;
    private String deliveryMethod;
    private BigDecimal deliveryFee;
    private LocalDate deliveryDate;
    private String description;
    private Integer targetQuantity;
    private Integer currentQuantity;
    private String status;
    private LocalDateTime deadline;
    private LocalDateTime createdAt;
    private Boolean isParticipating;
}
