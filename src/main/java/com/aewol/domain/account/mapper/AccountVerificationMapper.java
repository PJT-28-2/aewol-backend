package com.aewol.domain.account.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Map;

@Mapper
public interface AccountVerificationMapper {
    void insert(Map<String, Object> verification);
    Map<String, Object> findById(@Param("transactionId") String transactionId);
    void updateStatus(@Param("transactionId") String transactionId, @Param("status") String status);

    // registerAccount 전용 원자적 상태 전환. WHERE절에 status='VERIFIED'를 같이 걸어서,
    // 같은 transactionId로 동시에 두 번 들어와도 한쪽만 실제로 행을 갱신하고 반환값(영향
    // 행 수)으로 그 사실을 알 수 있게 한다(CodeRabbit 지적, 2026-08-06).
    int markUsedIfVerified(@Param("transactionId") String transactionId);
}
