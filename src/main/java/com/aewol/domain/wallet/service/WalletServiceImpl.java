package com.aewol.domain.wallet.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.wallet.dto.WalletResponse;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.HashMap;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    private final WalletMapper walletMapper;
    private final TransactionMapper transactionMapper;

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
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("충전 금액은 0보다 커야 합니다.");
        }
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }
        String walletId = String.valueOf(wallet.get("wallet_id"));
        if (walletMapper.addBalance(walletId, amount) == 0) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }
        Map<String, Object> transaction = new HashMap<>();
        transaction.put("walletId", walletId);
        transaction.put("txnType", "DEPOSIT");
        transaction.put("price", amount);
        transaction.put("memo", "지갑 충전");
        transaction.put("autoTagged", "N");
        transaction.put("txnDate", LocalDateTime.now());
        transactionMapper.insert(transaction);
        return getWallet(memberId);
    }

    private WalletResponse toWalletResponse(Map<String, Object> wallet) {
        return WalletResponse.builder()
                .walletId(String.valueOf(wallet.get("wallet_id")))
                .memberId(String.valueOf(wallet.get("member_id")))
                .totalBalance((BigDecimal) wallet.get("balance"))
                .build();
    }
}
