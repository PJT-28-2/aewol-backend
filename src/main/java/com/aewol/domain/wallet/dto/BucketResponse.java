package com.aewol.domain.wallet.dto;

import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;

@Getter
@Builder
public class BucketResponse {
    private String bucketId;
    private String walletId;
    private String petId;
    private String bucketType;
    private String bucketName;
    private BigDecimal targetAmount;
    private BigDecimal balance;
    private Boolean isSos;
}
