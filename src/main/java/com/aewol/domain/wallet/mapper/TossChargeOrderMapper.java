package com.aewol.domain.wallet.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TossChargeOrderMapper {

    void insert(Map<String, Object> order);

    Map<String, Object> findByOrderId(String orderId);

    int updateStatus(@Param("orderId") String orderId, @Param("status") String status);
}
