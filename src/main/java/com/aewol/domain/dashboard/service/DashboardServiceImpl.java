package com.aewol.domain.dashboard.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.dashboard.mapper.DashboardMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final DashboardMapper dashboardMapper;
    private final WalletMapper walletMapper;

    @Override
    public Map<String, Object> getMonthlySummary(String memberId, String month) {
        String walletId = getWalletId(memberId);
        List<Map<String, Object>> summary = dashboardMapper.getMonthlySummary(walletId, month);
        Map<String, Object> result = new HashMap<>();
        result.put("month", month);
        result.put("categories", summary);
        return result;
    }

    @Override
    public Map<String, Object> getCategoryBreakdown(String memberId, String petId) {
        String walletId = getWalletId(memberId);
        List<Map<String, Object>> breakdown = dashboardMapper.getCategoryBreakdown(walletId, petId);
        Map<String, Object> result = new HashMap<>();
        result.put("breakdown", breakdown);
        return result;
    }

    private String getWalletId(String memberId) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        return String.valueOf(wallet.get("wallet_id"));
    }
}
