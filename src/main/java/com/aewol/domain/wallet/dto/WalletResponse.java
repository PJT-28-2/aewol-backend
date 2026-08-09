package com.aewol.domain.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;

@Getter
@Builder
public class WalletResponse {
    private String walletId;
    private String memberId;
    @JsonProperty("walletBalance")
    private BigDecimal totalBalance;
}
