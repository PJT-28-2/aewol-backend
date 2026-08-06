package com.aewol.domain.account.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Map;

@Mapper
public interface AccountVerificationMapper {
    void insert(Map<String, Object> verification);
    Map<String, Object> findById(@Param("transactionId") String transactionId);
    void updateStatus(@Param("transactionId") String transactionId, @Param("status") String status);
}
