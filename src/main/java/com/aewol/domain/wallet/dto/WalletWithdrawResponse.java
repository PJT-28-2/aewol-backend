package com.aewol.domain.wallet.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WalletWithdrawResponse {
    private String transactionId;
    private BigDecimal walletBalance;
    private String accountId;
    private String bankName;
    private String accountNumberMasked;
    private LocalDateTime withdrawnAt;
}
