package com.aewol.domain.wallet.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.wallet.dto.WalletResponse;
import com.aewol.domain.wallet.mapper.WalletMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    private final WalletMapper walletMapper;

    @Override
    public WalletResponse getWallet(String memberId) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }
        return toWalletResponse(wallet);
    }

    @Override
    @Transactional
    public WalletResponse deposit(String memberId, BigDecimal amount) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }
        String walletId = String.valueOf(wallet.get("wallet_id"));
        BigDecimal currentBalance = (BigDecimal) wallet.get("balance");
        BigDecimal newBalance = currentBalance.add(amount);
        walletMapper.updateBalance(walletId, newBalance);

        return WalletResponse.builder()
                .walletId(walletId)
                .memberId(memberId)
                .totalBalance(newBalance)
                .build();
    }

    private WalletResponse toWalletResponse(Map<String, Object> wallet) {
        return WalletResponse.builder()
                .walletId(String.valueOf(wallet.get("wallet_id")))
                .memberId(String.valueOf(wallet.get("member_id")))
                .totalBalance((BigDecimal) wallet.get("balance"))
                .build();
    }
}
