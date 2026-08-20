package com.aewol.domain.grouppurchase.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GroupPurchaseListItemResponse {
    private Long id;
    private Long memberId;
    private String productName;
    private String category;
    private String status;
    private BigDecimal unitPrice;
    private BigDecimal groupPrice;
    private Integer currentQuantity;
    private Integer targetQuantity;
    @Getter(AccessLevel.NONE)
    private String dDay;
    private String badgeText;
    @JsonProperty("isParticipating")
    private Boolean isParticipating;
    private LocalDateTime createdAt;

    @JsonProperty("dDay")
    public String getDDay() {
        return dDay;
    }
}
