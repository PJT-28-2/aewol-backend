package com.aewol.domain.transaction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;

@Getter
@Builder
public class TransactionResponse {
    @JsonProperty("transactionId")
    private String txnId;
    private String walletId;
    private String bucketId;
    private String txnType;
    private BigDecimal amount;
    private String category;
    private String petId;
    private String merchantName;
    private String memo;
    private String autoTagged;
    private String taggedBy;
    @JsonProperty("transactionDate")
    private String txnDate;
    private String paymentMethod;
}
