package com.aewol.domain.account.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface AccountMapper {
    void insert(Map<String, Object> account);
    List<Map<String, Object>> findByMemberId(@Param("memberId") String memberId);
    Map<String, Object> findByAccountId(@Param("accountId") String accountId);
    // disconnectAccount의 대표 계좌 승격 판단에 쓰는 잠금 조회. 잠금 없이 읽으면 읽은
    // 뒤 다른 트랜잭션이 같은 계좌를 대표로 설정/해제해도 이 트랜잭션은 그 변경을 못
    // 보고 그대로 진행해 대표 계좌가 0개로 남을 수 있다(CodeRabbit 지적, 2026-08-08).
    Map<String, Object> findByAccountIdForUpdate(@Param("accountId") String accountId);
    void updateStatus(@Param("accountId") String accountId, @Param("status") String status);
    void clearPrimaryByMemberId(@Param("memberId") String memberId);
    int setPrimary(@Param("accountId") String accountId);
}
