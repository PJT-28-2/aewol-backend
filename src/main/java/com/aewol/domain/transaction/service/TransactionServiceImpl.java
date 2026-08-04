package com.aewol.domain.transaction.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.transaction.dto.PaymentRequest;
import com.aewol.domain.transaction.dto.TransactionResponse;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private final TransactionMapper transactionMapper;
    private final WalletMapper walletMapper;
    private final AutoTaggingService autoTaggingService;

    @Override
    @Transactional
    public TransactionResponse processPayment(String memberId, PaymentRequest request) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }

        String walletId = String.valueOf(wallet.get("wallet_id"));

        // 자동 태깅
        String category = autoTaggingService.categorize(request.getMerchantName());
        log.info("자동 태깅 결과 - merchant: {}, category: {}", request.getMerchantName(), category);

        // 지갑 잔액 차감 (V1에서 버킷 폐기 — 지갑 단일 잔액)
        BigDecimal balance = (BigDecimal) wallet.get("balance");
        if (balance.compareTo(request.getAmount()) < 0) {
            throw new BusinessException("잔액이 부족합니다.");
        }
        walletMapper.updateBalance(walletId, balance.subtract(request.getAmount()));

        // 거래 기록 생성 — txn_id는 AUTO_INCREMENT 생성 키
        Map<String, Object> txn = new HashMap<>();
        txn.put("walletId", walletId);
        txn.put("petId", request.getPetId());
        txn.put("txnType", "PAYMENT");
        txn.put("price", request.getAmount());
        txn.put("category", category);
        txn.put("merchantName", request.getMerchantName());
        txn.put("merchantCategoryCode", null);
        txn.put("memo", request.getMemo());
        txn.put("autoTagged", "Y");
        txn.put("txnDate", LocalDateTime.now());
        transactionMapper.insert(txn);

        return getTransaction(String.valueOf(txn.get("txnId")));
    }

    @Override
    public List<TransactionResponse> getTransactions(String memberId, String category, String petId) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }
        String walletId = String.valueOf(wallet.get("wallet_id"));
        return transactionMapper.findByWalletId(walletId, category, petId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public TransactionResponse getTransaction(String txnId) {
        Map<String, Object> txn = transactionMapper.findById(txnId);
        if (txn == null) {
            throw BusinessException.notFound("거래를 찾을 수 없습니다.");
        }
        return toResponse(txn);
    }

    private TransactionResponse toResponse(Map<String, Object> txn) {
        return TransactionResponse.builder()
                .txnId(String.valueOf(txn.get("txn_id")))
                .walletId(String.valueOf(txn.get("wallet_id")))
                .txnType((String) txn.get("txn_type"))
                .amount((BigDecimal) txn.get("price"))
                .category((String) txn.get("category"))
                .merchantName((String) txn.get("merchant_name"))
                .memo((String) txn.get("memo"))
                .autoTagged((String) txn.get("auto_tagged"))
                .txnDate(txn.get("txn_date") != null ? txn.get("txn_date").toString() : null)
                .build();
    }
}
