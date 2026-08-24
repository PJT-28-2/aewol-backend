package com.aewol.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.donation.mapper.DonationMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DonationRoundUpExecutorTest {

    @Mock DonationMapper donationMapper;

    private DonationRoundUpExecutor executor() {
        return new DonationRoundUpExecutor(donationMapper);
    }

    private Map<String, Object> candidate(String txnId, String memberId, String amount, String savingUnit) {
        Map<String, Object> row = new HashMap<>();
        row.put("txnId", txnId);
        row.put("memberId", memberId);
        row.put("amount", new BigDecimal(amount));
        row.put("savingUnit", new BigDecimal(savingUnit));
        return row;
    }

    private Map<String, Object> pot(String walletId) {
        Map<String, Object> row = new HashMap<>();
        row.put("wallet_id", walletId);
        return row;
    }

    @Test
    @DisplayName("결제액이 적립 단위로 딱 안 나눠떨어지면 차액을 저금통에 반영한다")
    void should_increasePotBalance_when_roundUpAmountIsPositive() {
        Map<String, Object> candidate = candidate("txn-1", "member-1", "34800", "1000");
        when(donationMapper.findPotByMemberId("member-1")).thenReturn(pot("wallet-1"));
        when(donationMapper.findPotForUpdate("member-1")).thenReturn(pot("wallet-1"));
        when(donationMapper.insertRoundUp(anyMap())).thenReturn(1);
        when(donationMapper.increasePotBalance(eq("wallet-1"), eq(new BigDecimal("200")))).thenReturn(1);
        when(donationMapper.completeRoundUp(any())).thenReturn(1);

        boolean result = executor().execute(candidate);

        assertTrue(result);
        verify(donationMapper).increasePotBalance("wallet-1", new BigDecimal("200"));
        verify(donationMapper).completeRoundUp(any());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(donationMapper).insertRoundUp(captor.capture());
        assertEquals("txn-1", captor.getValue().get("sourceTxnId"));
        assertEquals("PENDING", captor.getValue().get("status"));
        assertEquals(new BigDecimal("200"), captor.getValue().get("roundupAmount"));
    }

    @Test
    @DisplayName("저금통이 아직 없는 회원이면 만든 뒤 잠가서 처리한다")
    void should_createPot_when_potDoesNotExistYet() {
        Map<String, Object> candidate = candidate("txn-1", "member-1", "34800", "1000");
        when(donationMapper.findPotByMemberId("member-1")).thenReturn(null, pot("wallet-1"));
        when(donationMapper.findPotForUpdate("member-1")).thenReturn(pot("wallet-1"));
        when(donationMapper.insertRoundUp(anyMap())).thenReturn(1);
        when(donationMapper.increasePotBalance(eq("wallet-1"), any())).thenReturn(1);
        when(donationMapper.completeRoundUp(any())).thenReturn(1);

        boolean result = executor().execute(candidate);

        assertTrue(result);
        verify(donationMapper).insertPot(anyMap());
    }

    @Test
    @DisplayName("결제액이 적립 단위로 딱 나눠떨어지면 SKIPPED로만 기록하고 잔액은 건드리지 않는다")
    void should_recordSkipped_withoutTouchingBalance_when_paymentDividesEvenly() {
        Map<String, Object> candidate = candidate("txn-1", "member-1", "35000", "1000");
        when(donationMapper.findPotByMemberId("member-1")).thenReturn(pot("wallet-1"));
        when(donationMapper.findPotForUpdate("member-1")).thenReturn(pot("wallet-1"));
        when(donationMapper.insertRoundUp(anyMap())).thenReturn(1);

        boolean result = executor().execute(candidate);

        assertFalse(result);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(donationMapper).insertRoundUp(captor.capture());
        assertEquals("SKIPPED", captor.getValue().get("status"));
        assertEquals(BigDecimal.ZERO, captor.getValue().get("roundupAmount"));
        verify(donationMapper, never()).increasePotBalance(any(), any());
        verify(donationMapper, never()).completeRoundUp(any());
    }

    @Test
    @DisplayName("이미 처리된 건(재실행)이면 insertRoundUp의 영향 행이 0이라 부작용 없이 스킵한다")
    void should_skipWithoutSideEffects_when_alreadyProcessed() {
        Map<String, Object> candidate = candidate("txn-1", "member-1", "34800", "1000");
        when(donationMapper.findPotByMemberId("member-1")).thenReturn(pot("wallet-1"));
        when(donationMapper.findPotForUpdate("member-1")).thenReturn(pot("wallet-1"));
        // ON DUPLICATE KEY UPDATE source_txn_id = VALUES(source_txn_id)가 실제 값 변경이 없어 0을 반환하는
        // 상황을 재현한다 — 같은 source_txn_id로 이미 기록된 재실행.
        when(donationMapper.insertRoundUp(anyMap())).thenReturn(0);

        boolean result = executor().execute(candidate);

        assertFalse(result);
        verify(donationMapper, never()).increasePotBalance(any(), any());
        verify(donationMapper, never()).completeRoundUp(any());
    }

    @Test
    @DisplayName("결제액이 음수면 저금통 조회 없이 바로 스킵한다")
    void should_skipWithoutAnyQuery_when_paymentAmountIsNegative() {
        Map<String, Object> candidate = candidate("txn-1", "member-1", "-100", "1000");

        boolean result = executor().execute(candidate);

        assertFalse(result);
        verify(donationMapper, never()).findPotByMemberId(any());
        verify(donationMapper, never()).insertRoundUp(anyMap());
    }

    @Test
    @DisplayName("적립 단위가 0 이하면 저금통 조회 없이 바로 스킵한다")
    void should_skipWithoutAnyQuery_when_savingUnitIsNotPositive() {
        Map<String, Object> candidate = candidate("txn-1", "member-1", "34800", "0");

        boolean result = executor().execute(candidate);

        assertFalse(result);
        verify(donationMapper, never()).findPotByMemberId(any());
        verify(donationMapper, never()).insertRoundUp(anyMap());
    }

    @Test
    @DisplayName("잔액 반영이 실패(영향 행 0)하면 예외를 던지고 완료 처리로 넘어가지 않는다")
    void should_throw_andNotComplete_when_increasePotBalanceAffectsNoRows() {
        Map<String, Object> candidate = candidate("txn-1", "member-1", "34800", "1000");
        when(donationMapper.findPotByMemberId("member-1")).thenReturn(pot("wallet-1"));
        when(donationMapper.findPotForUpdate("member-1")).thenReturn(pot("wallet-1"));
        when(donationMapper.insertRoundUp(anyMap())).thenReturn(1);
        when(donationMapper.increasePotBalance(eq("wallet-1"), any())).thenReturn(0);

        assertThrows(BusinessException.class, () -> executor().execute(candidate));

        verify(donationMapper, never()).completeRoundUp(any());
    }

    @Test
    @DisplayName("완료 처리가 실패(영향 행 0)해도 잔액 반영 자체는 이미 시도된 뒤라 예외로 감지한다")
    void should_throw_when_completeRoundUpAffectsNoRows() {
        Map<String, Object> candidate = candidate("txn-1", "member-1", "34800", "1000");
        when(donationMapper.findPotByMemberId("member-1")).thenReturn(pot("wallet-1"));
        when(donationMapper.findPotForUpdate("member-1")).thenReturn(pot("wallet-1"));
        when(donationMapper.insertRoundUp(anyMap())).thenReturn(1);
        when(donationMapper.increasePotBalance(eq("wallet-1"), any())).thenReturn(1);
        when(donationMapper.completeRoundUp(any())).thenReturn(0);

        assertThrows(BusinessException.class, () -> executor().execute(candidate));

        verify(donationMapper).increasePotBalance(eq("wallet-1"), any());
    }
}
