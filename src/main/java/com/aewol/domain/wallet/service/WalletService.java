package com.aewol.domain.wallet.service;

import com.aewol.domain.wallet.dto.BucketCreateRequest;
import com.aewol.domain.wallet.dto.BucketResponse;
import com.aewol.domain.wallet.dto.WalletResponse;
import java.math.BigDecimal;
import java.util.List;

public interface WalletService {
    WalletResponse getWallet(String memberId);
    List<BucketResponse> getBuckets(String memberId);
    BucketResponse createBucket(String memberId, BucketCreateRequest request);
    void updateBucket(String bucketId, BucketCreateRequest request);
    void deleteBucket(String bucketId);
    WalletResponse deposit(String memberId, BigDecimal amount);
}
