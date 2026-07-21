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

        String walletId = (String) wallet.get("wallet_id");

        // 자동 태깅
        String category = autoTaggingService.categorize(request.getMerchantName());
        log.info("자동 태깅 결과 - merchant: {}, category: {}", request.getMerchantName(), category);

        // 매칭 버킷 검색
        List<Map<String, Object>> buckets = walletMapper.findBucketsByWalletId(walletId);
        String bucketId = null;

        for (Map<String, Object> bucket : buckets) {
            String bucketType = (String) bucket.get("bucket_type");
            String petId = (String) bucket.get("pet_id");
            boolean petMatch = request.getPetId() == null || request.getPetId().equals(petId);

            if (bucketType.equals(category) && petMatch) {
                BigDecimal balance = (BigDecimal) bucket.get("balance");
                if (balance.compareTo(request.getAmount()) >= 0) {
                    bucketId = (String) bucket.get("bucket_id");
                    walletMapper.updateBucket(Map.of(
                            "bucketId", bucketId,
                            "bucketName", bucket.get("bucket_name"),
                            "targetAmount", bucket.get("target_amount"),
                            "balance", balance.subtract(request.getAmount())
                    ));
                    break;
                }
            }
        }

        // 버킷에서 차감 못 했으면 지갑 잔액에서 차감
        if (bucketId == null) {
            BigDecimal totalBalance = (BigDecimal) wallet.get("total_balance");
            if (totalBalance.compareTo(request.getAmount()) < 0) {
                throw new BusinessException("잔액이 부족합니다.");
            }
            walletMapper.updateBalance(walletId, totalBalance.subtract(request.getAmount()));
        }

        // 거래 기록 생성
        String txnId = UUID.randomUUID().toString();
        Map<String, Object> txn = new HashMap<>();
        txn.put("txnId", txnId);
        txn.put("walletId", walletId);
        txn.put("bucketId", bucketId);
        txn.put("memberId", memberId);
        txn.put("txnType", "PAYMENT");
        txn.put("amount", request.getAmount());
        txn.put("category", category);
        txn.put("merchantName", request.getMerchantName());
        txn.put("merchantCategoryCode", null);
        txn.put("memo", request.getMemo());
        txn.put("autoTagged", "Y");
        txn.put("txnDate", LocalDateTime.now());
        transactionMapper.insert(txn);

        return getTransaction(txnId);
    }

    @Override
    public List<TransactionResponse> getTransactions(String memberId, String category, String petId) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }
        String walletId = (String) wallet.get("wallet_id");
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
                .txnId((String) txn.get("txn_id"))
                .walletId((String) txn.get("wallet_id"))
                .bucketId((String) txn.get("bucket_id"))
                .txnType((String) txn.get("txn_type"))
                .amount((BigDecimal) txn.get("amount"))
                .category((String) txn.get("category"))
                .merchantName((String) txn.get("merchant_name"))
                .memo((String) txn.get("memo"))
                .autoTagged((String) txn.get("auto_tagged"))
                .txnDate(txn.get("txn_date") != null ? txn.get("txn_date").toString() : null)
                .build();
    }
}
