package com.aewol.domain.wallet.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletServiceImplTest {
    @Mock WalletMapper walletMapper;
    @Mock TransactionMapper transactionMapper;

    @Test
    void should_throwException_when_depositAmountIsNotPositive() {
        WalletServiceImpl service = new WalletServiceImpl(walletMapper, transactionMapper);

        assertThrows(BusinessException.class,
                () -> service.deposit("member-1", BigDecimal.ZERO));
        verifyNoInteractions(walletMapper, transactionMapper);
    }

    @Test
    void should_addBalanceAndRecordTransaction_when_depositSucceeds() {
        WalletServiceImpl service = new WalletServiceImpl(walletMapper, transactionMapper);
        Map<String, Object> before = map("wallet_id", 1L, "member_id", 1L,
                "balance", new BigDecimal("10000"));
        Map<String, Object> after = map("wallet_id", 1L, "member_id", 1L,
                "balance", new BigDecimal("30000"));
        when(walletMapper.findByMemberId("1")).thenReturn(before, after);
        when(walletMapper.addBalance("1", new BigDecimal("20000"))).thenReturn(1);

        assertEquals(new BigDecimal("30000"),
                service.deposit("1", new BigDecimal("20000")).getTotalBalance());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(transactionMapper).insert(captor.capture());
        assertEquals("DEPOSIT", captor.getValue().get("txnType"));
        assertEquals(new BigDecimal("20000"), captor.getValue().get("price"));
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
