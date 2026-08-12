package com.aewol.domain.wallet.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WalletWithdrawalRequestMapper {

    Map<String, Object> findByMemberIdAndKey(@Param("memberId") String memberId,
                                              @Param("idempotencyKey") String idempotencyKey);

    void insertPending(Map<String, Object> request);

    int complete(Map<String, Object> result);
}
