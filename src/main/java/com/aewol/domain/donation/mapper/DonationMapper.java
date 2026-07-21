package com.aewol.domain.donation.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface DonationMapper {
    Map<String, Object> findPotByMemberId(@Param("memberId") String memberId);
    void insertPot(Map<String, Object> pot);
    void updatePotBalance(@Param("potId") String potId, @Param("balance") BigDecimal balance);
    void insertHistory(Map<String, Object> history);
    List<Map<String, Object>> findHistoryByPotId(@Param("potId") String potId);
}
