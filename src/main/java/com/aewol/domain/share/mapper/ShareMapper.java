package com.aewol.domain.share.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface ShareMapper {
    void insert(Map<String, Object> sharedAccess);
    void updateStatus(@Param("accessId") String accessId, @Param("status") String status);
    List<Map<String, Object>> findByWalletId(@Param("walletId") String walletId);
}
