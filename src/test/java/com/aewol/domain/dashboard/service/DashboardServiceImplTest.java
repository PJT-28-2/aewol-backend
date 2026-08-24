package com.aewol.domain.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.dashboard.mapper.DashboardMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardServiceImplTest {
    @Mock DashboardMapper dashboardMapper;
    @Mock WalletMapper walletMapper;

    @Test
    void should_groupCategoryBreakdownForRequestedMonth() {
        DashboardServiceImpl service = new DashboardServiceImpl(dashboardMapper, walletMapper);
        when(walletMapper.findByMemberId("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(dashboardMapper.getSpendingBreakdown("wallet-1", "2026-08"))
                .thenReturn(List.of(
                        map("category", "FOOD", "pet_id", 1L, "pet_name", "소로", "amount", new BigDecimal("30000")),
                        map("category", "FOOD", "pet_id", 2L, "pet_name", "나비", "amount", new BigDecimal("20000"))));

        Map<String, Object> result = service.getCategoryBreakdown("member-1", "CATEGORY", "2026-08");

        assertEquals("2026-08", result.get("yearMonth"));
        assertEquals(new BigDecimal("50000"), result.get("totalAmount"));
        assertEquals(1, ((List<?>) result.get("items")).size());
    }

    @Test
    void should_mergeNullCategoryIntoEtcWithoutDuplicatingPet() {
        DashboardServiceImpl service = new DashboardServiceImpl(dashboardMapper, walletMapper);
        when(walletMapper.findByMemberId("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(dashboardMapper.getSpendingBreakdown("wallet-1", "2026-08"))
                .thenReturn(List.of(
                        map("category", "ETC", "pet_id", 1L, "pet_name", "포리", "amount", new BigDecimal("2000")),
                        map("category", null, "pet_id", 1L, "pet_name", "포리", "amount", new BigDecimal("24000"))));

        Map<String, Object> result = service.getCategoryBreakdown("member-1", "CATEGORY", "2026-08");

        List<?> items = (List<?>) result.get("items");
        assertEquals(1, items.size());
        Map<?, ?> etc = (Map<?, ?>) items.get(0);
        assertEquals("ETC", etc.get("category"));
        assertEquals(new BigDecimal("26000"), etc.get("amount"));
        List<?> pets = (List<?>) etc.get("petBreakdown");
        assertEquals(1, pets.size());
        assertEquals(new BigDecimal("26000"), ((Map<?, ?>) pets.get(0)).get("amount"));
    }

    @Test
    void should_buildHomeSummaryWithWalletAndPetSpending() {
        DashboardServiceImpl service = new DashboardServiceImpl(dashboardMapper, walletMapper);
        when(walletMapper.findByMemberId("member-1")).thenReturn(
                map("wallet_id", "wallet-1", "balance", new BigDecimal("482600")));
        when(dashboardMapper.getMonthlyTotal("wallet-1", "2026-08"))
                .thenReturn(new BigDecimal("120000"));
        when(dashboardMapper.getMonthlyTotal("wallet-1", "2026-07"))
                .thenReturn(new BigDecimal("100000"));
        when(dashboardMapper.getPetMonthlySummary("wallet-1", "2026-08"))
                .thenReturn(List.of(map("pet_id", 1L, "pet_name", "소로", "amount", new BigDecimal("60000"))));

        Map<String, Object> result = service.getMonthlySummary("member-1", "2026-08");

        assertEquals(new BigDecimal("482600"), result.get("walletBalance"));
        verify(walletMapper, times(1)).findByMemberId("member-1");
        Map<?, ?> monthlySpend = (Map<?, ?>) result.get("monthlySpend");
        assertEquals(new BigDecimal("120000"), monthlySpend.get("totalAmount"));
        assertEquals(20, monthlySpend.get("changeRate"));
    }

    @Test
    void should_throwException_when_monthFormatIsInvalid() {
        DashboardServiceImpl service = new DashboardServiceImpl(dashboardMapper, walletMapper);

        assertThrows(BusinessException.class,
                () -> service.getMonthlySummary("member-1", "2026-8"));
        verifyNoInteractions(walletMapper, dashboardMapper);
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
