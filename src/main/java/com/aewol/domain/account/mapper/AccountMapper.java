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
    void updateStatus(@Param("accountId") String accountId, @Param("status") String status);
    void clearPrimaryByMemberId(@Param("memberId") String memberId);
    int setPrimary(@Param("accountId") String accountId);
}
