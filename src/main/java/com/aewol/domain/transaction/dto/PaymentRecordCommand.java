package com.aewol.domain.transaction.dto;

import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;

@Getter
@Builder
public class PaymentRecordCommand {
    private String memberId;
    private String merchantName;
    private BigDecimal amount;
    private String petId;
    private String memo;
    private String paymentKey;
    private String orderId;
}
