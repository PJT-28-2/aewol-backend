package com.aewol.domain.dashboard.service;

import java.util.Map;

public interface DashboardService {
    Map<String, Object> getMonthlySummary(String memberId, String month);
    Map<String, Object> getCategoryBreakdown(String memberId, String petId);
}
