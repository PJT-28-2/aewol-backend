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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardServiceImplTest {
    @Mock DashboardMapper dashboardMapper;
    @Mock WalletMapper walletMapper;

    @Test
    void should_queryCategoryBreakdownForRequestedMonth() {
        DashboardServiceImpl service = new DashboardServiceImpl(dashboardMapper, walletMapper);
        when(walletMapper.findByMemberId("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(dashboardMapper.getCategoryBreakdown("wallet-1", null, "2026-08"))
                .thenReturn(List.of());

        Map<String, Object> result = service.getCategoryBreakdown("member-1", null, "2026-08");

        assertEquals("2026-08", result.get("yearMonth"));
        verify(dashboardMapper).getCategoryBreakdown("wallet-1", null, "2026-08");
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
