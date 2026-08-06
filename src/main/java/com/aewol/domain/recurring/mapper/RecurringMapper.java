package com.aewol.domain.recurring.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface RecurringMapper {
    Map<String, Object> findById(@Param("recurringId") String recurringId);
    List<Map<String, Object>> findByWalletId(@Param("walletId") String walletId);
    void insert(Map<String, Object> recurring);
    void deactivate(@Param("recurringId") String recurringId);
    List<Map<String, Object>> findDuePayments(@Param("date") String date);
}
