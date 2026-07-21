package com.aewol.domain.activity.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface ActivityLogMapper {

    void insert(Map<String, Object> log);

    List<Map<String, Object>> findByWalletId(@Param("walletId") String walletId);

    List<Map<String, Object>> findByMemberId(@Param("memberId") String memberId);
}
