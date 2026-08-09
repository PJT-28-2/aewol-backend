package com.aewol.domain.dashboard.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface DashboardMapper {
    java.math.BigDecimal getMonthlyTotal(@Param("walletId") String walletId,
                                         @Param("month") String month);
    List<Map<String, Object>> getPetMonthlySummary(@Param("walletId") String walletId,
                                                   @Param("month") String month);
    List<Map<String, Object>> getSpendingBreakdown(@Param("walletId") String walletId,
                                                   @Param("yearMonth") String yearMonth);
}
