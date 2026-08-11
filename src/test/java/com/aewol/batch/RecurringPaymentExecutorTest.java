package com.aewol.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.domain.recurring.mapper.RecurringMapper;
import com.aewol.domain.recurring.service.RecurringServiceImpl;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecurringPaymentExecutorTest {

    @Mock WalletMapper walletMapper;
    @Mock TransactionMapper transactionMapper;
    @Mock RecurringMapper recurringMapper;

    private RecurringPaymentExecutor executor() {
        return new RecurringPaymentExecutor(walletMapper, transactionMapper, recurringMapper);
    }

    private Map<String, Object> due(int paymentDay) {
        Map<String, Object> due = new HashMap<>();
        due.put("recurring_id", 1L);
        due.put("wallet_id", "wallet-1");
        due.put("pet_id", "pet-1");
        due.put("product_name", "강아지 사료 정기배송");
        due.put("category", "FOOD");
        due.put("price", new BigDecimal("32000"));
        due.put("payment_day", paymentDay);
        return due;
    }

    @Test
    void should_deductRecordAndAdvance_when_balanceIsSufficient() {
        when(walletMapper.deductBalance(eq("wallet-1"), any(BigDecimal.class))).thenReturn(1);
        int paymentDay = 15;

        boolean result = executor().execute(due(paymentDay));

        assertTrue(result);
        // 거래 기록 생성
        ArgumentCaptor<Map<String, Object>> txnCaptor = ArgumentCaptor.forClass(Map.class);
        verify(transactionMapper).insert(txnCaptor.capture());
        assertEquals("PAYMENT", txnCaptor.getValue().get("txnType"));
        assertEquals("강아지 사료 정기배송", txnCaptor.getValue().get("merchantName"));
        assertEquals("FOOD", txnCaptor.getValue().get("category"));
        // 다음 주기 결제일로 갱신
        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(recurringMapper).updateNextPaymentDate(eq("1"), dateCaptor.capture());
        assertEquals(RecurringServiceImpl.nextPaymentDate(paymentDay, LocalDate.now()),
                dateCaptor.getValue());
    }

    @Test
    void should_skipWithoutRecordOrAdvance_when_balanceIsInsufficient() {
        when(walletMapper.deductBalance(eq("wallet-1"), any(BigDecimal.class))).thenReturn(0);

        boolean result = executor().execute(due(15));

        assertFalse(result);
        verify(transactionMapper, never()).insert(anyMap());
        verify(recurringMapper, never()).updateNextPaymentDate(anyString(), any(LocalDate.class));
    }
}
