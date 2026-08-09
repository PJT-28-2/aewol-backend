package com.aewol.domain.dashboard.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface DashboardMapper {
    List<Map<String, Object>> getMonthlySummary(@Param("walletId") String walletId, @Param("month") String month);
    List<Map<String, Object>> getCategoryBreakdown(@Param("walletId") String walletId,
                                                   @Param("petId") String petId,
                                                   @Param("yearMonth") String yearMonth);
}
