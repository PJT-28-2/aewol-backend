package com.aewol.domain.wallet.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.wallet.dto.ExternalChargeCommand;
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

    @Override
    @Transactional
    public WalletResponse depositExternal(ExternalChargeCommand command) {
        // TossChargeRequest의 @DecimalMin/@Digits는 여기까지 오지 않는다 —
        // 이 금액은 요청 DTO가 아니라 Toss confirm 응답의 totalAmount에서 온다.
        // 외부 시스템이 준 값이므로 0/음수/이상값을 여기서 다시 막는다.
        BigDecimal amount = command.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("충전 금액은 0보다 커야 합니다.");
        }
        Map<String, Object> wallet = walletMapper.findByMemberId(command.getMemberId());
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
        transaction.put("memo", "지갑 충전 (TossPayments)");
        // 충전은 지출이 아니므로 자동 태깅 대상이 아니다. petId/category도 해당 없음.
        transaction.put("autoTagged", "N");
        transaction.put("txnDate", LocalDateTime.now());
        transaction.put("paymentKey", command.getPaymentKey());
        transaction.put("orderId", command.getOrderId());
        // payment_key(UNIQUE) 덕분에 같은 승인건이 두 번 적립되면 DuplicateKeyException으로 막힌다.
        transactionMapper.insertTossPayment(transaction);
        return getWallet(command.getMemberId());
    }

    private WalletResponse toWalletResponse(Map<String, Object> wallet) {
        return WalletResponse.builder()
                .walletId(String.valueOf(wallet.get("wallet_id")))
                .memberId(String.valueOf(wallet.get("member_id")))
                .totalBalance((BigDecimal) wallet.get("balance"))
                .build();
    }
}
