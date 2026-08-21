package com.aewol.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.common.exception.BusinessException;
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
        row.put("category", "사료");
        row.put("target_quantity", 10);
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
        when(walletMapper.addBalance("wallet-1", new BigDecimal("50000"))).thenReturn(1);

        boolean result = executor().execute(candidate);

        assertTrue(result);
        verify(groupPurchaseMapper).decreaseQuantityForExpired("1", 2);
        verify(walletMapper).addBalance("wallet-1", new BigDecimal("50000"));
        ArgumentCaptor<Map<String, Object>> txnCaptor = ArgumentCaptor.forClass(Map.class);
        verify(transactionMapper).insert(txnCaptor.capture());
        assertEquals("REFUND", txnCaptor.getValue().get("txnType"));
        assertEquals(new BigDecimal("50000"), txnCaptor.getValue().get("price"));
        assertEquals("FOOD", txnCaptor.getValue().get("category"));
        String memo = (String) txnCaptor.getValue().get("memo");
        assertTrue(memo.contains("마감 미달 자동환불"), "leave()의 '참여 취소 환불' 문구와 구분되는 메모여야 한다: " + memo);
        assertFalse(memo.contains("gpId"), "거래내역 memo에 내부 gpId가 노출되면 안 된다: " + memo);
    }

    @Test
    void should_returnFalseWithoutSideEffects_when_alreadyProcessedByAnotherPath() {
        Map<String, Object> candidate = candidate("1", "member-1", 2, "50000");
        // leave()나 작성자 취소가 먼저 CANCELLED로 바꿔놓은 상황을 재현한다.
        when(groupPurchaseMapper.cancelParticipant(eq("1"), eq("member-1"), any())).thenReturn(0);

        boolean result = executor().execute(candidate);

        assertFalse(result);
        verify(groupPurchaseMapper, never()).decreaseQuantityForExpired(any(), anyInt());
        verify(walletMapper, never()).addBalance(any(), any());
        verify(transactionMapper, never()).insert(anyMap());
    }

    @Test
    void should_throwAndNotInsertTransaction_when_addBalanceAffectsNoRows() {
        Map<String, Object> candidate = candidate("1", "member-1", 2, "50000");
        when(groupPurchaseMapper.cancelParticipant(eq("1"), eq("member-1"), any())).thenReturn(1);
        when(groupPurchaseMapper.findById("1")).thenReturn(gp("1"));
        Map<String, Object> wallet = new HashMap<>();
        wallet.put("wallet_id", "wallet-1");
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet);
        // 지갑이 조회 이후 삭제되는 등, 원자적 UPDATE의 영향 행이 0인 상황(잔액 환급 실패)을 재현한다.
        when(walletMapper.addBalance("wallet-1", new BigDecimal("50000"))).thenReturn(0);

        assertThrows(BusinessException.class, () -> executor().execute(candidate));

        // 실제로 잔액이 안 들어갔는데 거래내역만 남는 유령 환불 기록을 막는다.
        verify(transactionMapper, never()).insert(anyMap());
    }

    @Test
    void should_stillRefund_when_groupPurchaseTargetQuantityIsNotPositive() {
        // target_quantity <= 0은 정상 생성 경로로는 나올 수 없는 레거시/비정상 데이터다(GroupPurchaseServiceImpl
        // isTargetReached 참고). 목록/상세에서는 이런 데이터를 숨기지만, 이미 결제한 참여자의 환불까지
        // 막을 이유는 없으므로 배치는 경고 로그만 남기고 정상적으로 환불을 진행해야 한다.
        Map<String, Object> candidate = candidate("1", "member-1", 2, "50000");
        Map<String, Object> corruptedGp = gp("1");
        corruptedGp.put("target_quantity", 0);
        when(groupPurchaseMapper.cancelParticipant(eq("1"), eq("member-1"), any())).thenReturn(1);
        when(groupPurchaseMapper.findById("1")).thenReturn(corruptedGp);
        Map<String, Object> wallet = new HashMap<>();
        wallet.put("wallet_id", "wallet-1");
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet);
        when(walletMapper.addBalance("wallet-1", new BigDecimal("50000"))).thenReturn(1);

        boolean result = executor().execute(candidate);

        assertTrue(result);
        verify(walletMapper).addBalance("wallet-1", new BigDecimal("50000"));
        verify(transactionMapper).insert(anyMap());
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
