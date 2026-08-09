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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final DashboardMapper dashboardMapper;
    private final WalletMapper walletMapper;

    @Override
    public Map<String, Object> getMonthlySummary(String memberId, String month) {
        String targetMonth = normalizeMonth(month);
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        String walletId = String.valueOf(wallet.get("wallet_id"));
        BigDecimal currentTotal = zeroIfNull(dashboardMapper.getMonthlyTotal(walletId, targetMonth));
        String previousMonth = YearMonth.parse(targetMonth).minusMonths(1).toString();
        BigDecimal previousTotal = zeroIfNull(dashboardMapper.getMonthlyTotal(walletId, previousMonth));
        List<Map<String, Object>> petRows = dashboardMapper.getPetMonthlySummary(walletId, targetMonth);

        List<Map<String, Object>> petSummary = new ArrayList<>();
        List<Map<String, Object>> petSpendChart = new ArrayList<>();
        for (Map<String, Object> row : petRows) {
            BigDecimal amount = number(row.get("amount"));
            Map<String, Object> pet = petItem(row, amount);
            petSummary.add(pet);
            Map<String, Object> chart = new LinkedHashMap<>(pet);
            chart.put("ratio", ratio(amount, currentTotal));
            petSpendChart.add(chart);
        }

        Map<String, Object> monthlySpend = new LinkedHashMap<>();
        monthlySpend.put("totalAmount", currentTotal);
        monthlySpend.put("changeRate", changeRate(currentTotal, previousTotal));
        Map<String, Object> result = new HashMap<>();
        result.put("walletBalance", number(wallet.get("balance")));
        result.put("monthlySpend", monthlySpend);
        result.put("petSummary", petSummary);
        result.put("petSpendChart", petSpendChart);
        return result;
    }

    @Override
    public Map<String, Object> getCategoryBreakdown(String memberId, String groupBy, String yearMonth) {
        String normalizedGroupBy = normalizeGroupBy(groupBy);
        String targetMonth = normalizeMonth(yearMonth);
        String walletId = getWalletId(memberId);
        List<Map<String, Object>> rows = dashboardMapper.getSpendingBreakdown(walletId, targetMonth);
        BigDecimal totalAmount = rows.stream()
                .map(row -> number(row.get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> result = new HashMap<>();
        result.put("yearMonth", targetMonth);
        result.put("groupBy", normalizedGroupBy);
        result.put("totalAmount", totalAmount);
        result.put("items", "PET".equals(normalizedGroupBy)
                ? groupByPet(rows) : groupByCategory(rows));
        return result;
    }

    private List<Map<String, Object>> groupByCategory(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String category = String.valueOf(row.get("category"));
            Map<String, Object> item = grouped.computeIfAbsent(category, key -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("category", key);
                value.put("amount", BigDecimal.ZERO);
                value.put("petBreakdown", new ArrayList<Map<String, Object>>());
                return value;
            });
            BigDecimal amount = number(row.get("amount"));
            item.put("amount", number(item.get("amount")).add(amount));
            if (row.get("pet_id") != null) {
                petBreakdown(item).add(petItem(row, amount));
            }
        }
        return new ArrayList<>(grouped.values());
    }

    private List<Map<String, Object>> groupByPet(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (row.get("pet_id") == null) continue;
            String petId = String.valueOf(row.get("pet_id"));
            Map<String, Object> item = grouped.computeIfAbsent(petId, key -> {
                Map<String, Object> value = petItem(row, BigDecimal.ZERO);
                return value;
            });
            item.put("amount", number(item.get("amount")).add(number(row.get("amount"))));
        }
        return new ArrayList<>(grouped.values());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> petBreakdown(Map<String, Object> item) {
        return (List<Map<String, Object>>) item.get("petBreakdown");
    }

    private Map<String, Object> petItem(Map<String, Object> row, BigDecimal amount) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("petId", row.get("pet_id") == null ? null : String.valueOf(row.get("pet_id")));
        item.put("petName", row.get("pet_name"));
        item.put("amount", amount);
        return item;
    }

    private String normalizeGroupBy(String groupBy) {
        if (groupBy == null || "CATEGORY".equalsIgnoreCase(groupBy)) return "CATEGORY";
        if ("PET".equalsIgnoreCase(groupBy)) return "PET";
        throw new BusinessException("groupBy는 CATEGORY 또는 PET이어야 합니다.");
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal number(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private int ratio(BigDecimal amount, BigDecimal total) {
        if (total.compareTo(BigDecimal.ZERO) == 0) return 0;
        return amount.multiply(BigDecimal.valueOf(100))
                .divide(total, 0, RoundingMode.HALF_UP).intValue();
    }

    private int changeRate(BigDecimal current, BigDecimal previous) {
        if (previous.compareTo(BigDecimal.ZERO) == 0) return current.compareTo(BigDecimal.ZERO) == 0 ? 0 : 100;
        return current.subtract(previous).multiply(BigDecimal.valueOf(100))
                .divide(previous, 0, RoundingMode.HALF_UP).intValue();
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
