package com.aewol.domain.wallet.service;

import com.aewol.domain.wallet.dto.WalletResponse;
import java.math.BigDecimal;

public interface WalletService {
    WalletResponse getWallet(String memberId);
    WalletResponse deposit(String memberId, BigDecimal amount);
}
