package com.aewol.domain.wallet.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface WalletMapper {
    Map<String, Object> findByMemberId(@Param("memberId") String memberId);
    Map<String, Object> findById(@Param("walletId") String walletId);
    void insert(Map<String, Object> wallet);
    void updateBalance(@Param("walletId") String walletId, @Param("totalBalance") BigDecimal totalBalance);
    List<Map<String, Object>> findBucketsByWalletId(@Param("walletId") String walletId);
    Map<String, Object> findBucketById(@Param("bucketId") String bucketId);
    void insertBucket(Map<String, Object> bucket);
    void updateBucket(Map<String, Object> bucket);
    void deleteBucket(@Param("bucketId") String bucketId);
}
