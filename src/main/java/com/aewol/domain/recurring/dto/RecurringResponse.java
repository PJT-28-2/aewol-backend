package com.aewol.domain.recurring.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RecurringResponse {
    private String recurringId;
    private String itemName;
    private BigDecimal price;
    private Integer cycleDay;
    private String category;
    private String petId;
    private String nextPaymentDate;
}
