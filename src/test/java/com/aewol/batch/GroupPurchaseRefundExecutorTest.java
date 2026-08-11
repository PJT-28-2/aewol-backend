package com.aewol.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.domain.grouppurchase.mapper.GroupPurchaseMapper;
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
class GroupPurchaseRefundExecutorTest {

    @Mock GroupPurchaseMapper groupPurchaseMapper;
    @Mock WalletMapper walletMapper;
    @Mock TransactionMapper transactionMapper;

    private GroupPurchaseRefundExecutor executor() {
        return new GroupPurchaseRefundExecutor(groupPurchaseMapper, walletMapper, transactionMapper);
    }

    private Map<String, Object> candidate(String gpId, String memberId, int quantity, String paidAmount) {
        Map<String, Object> row = new HashMap<>();
        row.put("gp_id", gpId);
        row.put("member_id", memberId);
        row.put("purchase_quantity", quantity);
        row.put("paid_amount", paidAmount == null ? null : new BigDecimal(paidAmount));
        return row;
    }

    private Map<String, Object> gp(String gpId) {
        Map<String, Object> row = new HashMap<>();
        row.put("gp_id", gpId);
        row.put("product_name", "사료 5kg");
        return row;
    }

    @Test
    void should_cancelDecreaseAndRefund_when_candidateIsPaid() {
        Map<String, Object> candidate = candidate("1", "member-1", 2, "50000");
        when(groupPurchaseMapper.cancelParticipant(eq("1"), eq("member-1"), any())).thenReturn(1);
        when(groupPurchaseMapper.findById("1")).thenReturn(gp("1"));
        Map<String, Object> wallet = new HashMap<>();
        wallet.put("wallet_id", "wallet-1");
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet);

        boolean result = executor().execute(candidate);

        assertTrue(result);
        verify(groupPurchaseMapper).decreaseQuantityForExpired("1", 2);
        verify(walletMapper).addBalance("wallet-1", new BigDecimal("50000"));
        ArgumentCaptor<Map<String, Object>> txnCaptor = ArgumentCaptor.forClass(Map.class);
        verify(transactionMapper).insert(txnCaptor.capture());
        assertEquals("DEPOSIT", txnCaptor.getValue().get("txnType"));
        assertEquals(new BigDecimal("50000"), txnCaptor.getValue().get("price"));
        String memo = (String) txnCaptor.getValue().get("memo");
        assertTrue(memo.contains("마감 미달 자동환불"), "leave()의 '참여 취소 환불' 문구와 구분되는 메모여야 한다: " + memo);
    }

    @Test
    void should_returnFalseWithoutSideEffects_when_alreadyProcessedByAnotherPath() {
        Map<String, Object> candidate = candidate("1", "member-1", 2, "50000");
        // leave()나 작성자 취소가 먼저 CANCELLED로 바꿔놓은 상황을 재현한다.
        when(groupPurchaseMapper.cancelParticipant(eq("1"), eq("member-1"), any())).thenReturn(0);

        boolean result = executor().execute(candidate);

        assertFalse(result);
        verify(groupPurchaseMapper, never()).decreaseQuantityForExpired(any(), any(Integer.class));
        verify(walletMapper, never()).addBalance(any(), any());
        verify(transactionMapper, never()).insert(anyMap());
    }

    @Test
    void should_skipRefund_when_paidAmountIsNull() {
        Map<String, Object> candidate = candidate("1", "member-1", 1, null);
        when(groupPurchaseMapper.cancelParticipant(eq("1"), eq("member-1"), any())).thenReturn(1);

        boolean result = executor().execute(candidate);

        assertTrue(result);
        verify(groupPurchaseMapper).decreaseQuantityForExpired("1", 1);
        verify(walletMapper, never()).findByMemberId(any());
        verify(transactionMapper, never()).insert(anyMap());
    }
}
