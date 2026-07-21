package com.aewol.domain.transaction.dto;

import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;

@Getter
@Builder
public class TransactionResponse {
    private String txnId;
    private String walletId;
    private String bucketId;
    private String txnType;
    private BigDecimal amount;
    private String category;
    private String merchantName;
    private String memo;
    private String autoTagged;
    private String txnDate;
}
