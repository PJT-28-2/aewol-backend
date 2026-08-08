package com.aewol.domain.account.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Map;

@Mapper
public interface AccountVerificationMapper {
    void insert(Map<String, Object> verification);
    Map<String, Object> findById(@Param("transactionId") String transactionId);

    // confirm 전용 — 트랜잭션 안에서 이 행을 잠근 채로 한도 검사/값 비교/상태 갱신을
    // 순서대로 수행해야 여러 confirm 요청이 동시에 같은 attempt_count를 읽고 모두
    // 통과하는 경합을 막을 수 있다(CodeRabbit 지적, 2026-08-07).
    Map<String, Object> findByIdForUpdate(@Param("transactionId") String transactionId);
    void updateStatus(@Param("transactionId") String transactionId, @Param("status") String status);
    void incrementAttemptCount(@Param("transactionId") String transactionId);

    // registerAccount 전용 원자적 상태 전환. WHERE절에 status='VERIFIED'를 같이 걸어서,
    // 같은 transactionId로 동시에 두 번 들어와도 한쪽만 실제로 행을 갱신하고 반환값(영향
    // 행 수)으로 그 사실을 알 수 있게 한다(CodeRabbit 지적, 2026-08-06).
    int markUsedIfVerified(@Param("transactionId") String transactionId);
}
