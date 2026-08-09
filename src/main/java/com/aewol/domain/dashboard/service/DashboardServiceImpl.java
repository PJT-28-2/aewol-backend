package com.aewol.domain.dashboard.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.dashboard.mapper.DashboardMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final DashboardMapper dashboardMapper;
    private final WalletMapper walletMapper;

    @Override
    public Map<String, Object> getMonthlySummary(String memberId, String month) {
        String targetMonth = normalizeMonth(month);
        String walletId = getWalletId(memberId);
        List<Map<String, Object>> summary = dashboardMapper.getMonthlySummary(walletId, targetMonth);
        Map<String, Object> result = new HashMap<>();
        result.put("month", targetMonth);
        result.put("categories", summary);
        return result;
    }

    @Override
    public Map<String, Object> getCategoryBreakdown(String memberId, String petId, String yearMonth) {
        String targetMonth = normalizeMonth(yearMonth);
        String walletId = getWalletId(memberId);
        List<Map<String, Object>> breakdown = dashboardMapper.getCategoryBreakdown(walletId, petId, targetMonth);
        Map<String, Object> result = new HashMap<>();
        result.put("yearMonth", targetMonth);
        result.put("breakdown", breakdown);
        return result;
    }

    private String normalizeMonth(String month) {
        if (month == null || month.isBlank()) return YearMonth.now().toString();
        try {
            return YearMonth.parse(month).toString();
        } catch (DateTimeParseException exception) {
            throw new BusinessException("월은 yyyy-MM 형식이어야 합니다.");
        }
    }

    private String getWalletId(String memberId) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        return String.valueOf(wallet.get("wallet_id"));
    }
}
