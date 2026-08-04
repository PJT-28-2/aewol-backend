package com.aewol.domain.wallet.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.util.Map;

/**
 * V1에서 버킷이 폐기되고 지갑이 회원당 MAIN/DONATION 타입별 1개로 통합됨.
 * findByMemberId는 MAIN 지갑을 반환한다 (기부함은 donation 도메인이 직접 조회).
 */
@Mapper
public interface WalletMapper {
    Map<String, Object> findByMemberId(@Param("memberId") String memberId);
    Map<String, Object> findById(@Param("walletId") String walletId);
    void insert(Map<String, Object> wallet);
    void updateBalance(@Param("walletId") String walletId, @Param("balance") BigDecimal balance);
}
