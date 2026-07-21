package com.aewol.domain.wallet.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.wallet.dto.BucketCreateRequest;
import com.aewol.domain.wallet.dto.BucketResponse;
import com.aewol.domain.wallet.dto.WalletResponse;
import com.aewol.domain.wallet.mapper.WalletMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

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
    public List<BucketResponse> getBuckets(String memberId) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }
        String walletId = (String) wallet.get("wallet_id");
        return walletMapper.findBucketsByWalletId(walletId).stream()
                .map(this::toBucketResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public BucketResponse createBucket(String memberId, BucketCreateRequest request) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }

        String bucketId = UUID.randomUUID().toString();
        Map<String, Object> bucket = new HashMap<>();
        bucket.put("bucketId", bucketId);
        bucket.put("walletId", wallet.get("wallet_id"));
        bucket.put("petId", request.getPetId());
        bucket.put("bucketType", request.getBucketType());
        bucket.put("bucketName", request.getBucketName());
        bucket.put("targetAmount", request.getTargetAmount() != null ? request.getTargetAmount() : BigDecimal.ZERO);
        bucket.put("balance", BigDecimal.ZERO);
        bucket.put("isSos", request.getIsSos() != null && request.getIsSos() ? 1 : 0);
        walletMapper.insertBucket(bucket);

        return toBucketResponse(walletMapper.findBucketById(bucketId));
    }

    @Override
    @Transactional
    public void updateBucket(String bucketId, BucketCreateRequest request) {
        Map<String, Object> existing = walletMapper.findBucketById(bucketId);
        if (existing == null) {
            throw BusinessException.notFound("버킷을 찾을 수 없습니다.");
        }
        Map<String, Object> bucket = new HashMap<>();
        bucket.put("bucketId", bucketId);
        bucket.put("bucketName", request.getBucketName());
        bucket.put("targetAmount", request.getTargetAmount());
        bucket.put("balance", existing.get("balance"));
        walletMapper.updateBucket(bucket);
    }

    @Override
    @Transactional
    public void deleteBucket(String bucketId) {
        if (walletMapper.findBucketById(bucketId) == null) {
            throw BusinessException.notFound("버킷을 찾을 수 없습니다.");
        }
        walletMapper.deleteBucket(bucketId);
    }

    @Override
    @Transactional
    public WalletResponse deposit(String memberId, BigDecimal amount) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }
        String walletId = (String) wallet.get("wallet_id");
        BigDecimal currentBalance = (BigDecimal) wallet.get("total_balance");
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
                .walletId((String) wallet.get("wallet_id"))
                .memberId((String) wallet.get("member_id"))
                .totalBalance((BigDecimal) wallet.get("total_balance"))
                .build();
    }

    private BucketResponse toBucketResponse(Map<String, Object> bucket) {
        return BucketResponse.builder()
                .bucketId((String) bucket.get("bucket_id"))
                .walletId((String) bucket.get("wallet_id"))
                .petId((String) bucket.get("pet_id"))
                .bucketType((String) bucket.get("bucket_type"))
                .bucketName((String) bucket.get("bucket_name"))
                .targetAmount((BigDecimal) bucket.get("target_amount"))
                .balance((BigDecimal) bucket.get("balance"))
                .isSos(bucket.get("is_sos") != null && ((Number) bucket.get("is_sos")).intValue() == 1)
                .build();
    }
}
