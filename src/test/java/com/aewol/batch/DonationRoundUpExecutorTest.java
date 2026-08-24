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
import java.time.LocalDate;
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

    @Test
    @DisplayName("31,275원을 1,000원 단위로 깎으면 275원을 저금통으로 옮긴다")
    void should_transferRemainder_when_walletHasSpareChange() {
        LocalDate today = LocalDate.now(DonationRoundUpExecutor.SEOUL);
        when(donationMapper.findSettingsForUpdate("member-1")).thenReturn(settings(true, "1000", null));
        when(donationMapper.findMainWalletForUpdate("member-1"))
                .thenReturn(wallet("wallet-1", "31275"));
        when(donationMapper.findPotByMemberId("member-1")).thenReturn(wallet("pot-1", "0"));
        when(donationMapper.findPotForUpdate("member-1")).thenReturn(wallet("pot-1", "0"));
        when(donationMapper.decreaseMainWalletBalance("wallet-1", new BigDecimal("275"))).thenReturn(1);
        when(donationMapper.increasePotBalance("pot-1", new BigDecimal("275"))).thenReturn(1);
        when(donationMapper.markSpareTrimmed("member-1", today)).thenReturn(1);

        boolean result = executor().execute(candidate("member-1"));

        assertTrue(result);
        verify(donationMapper).decreaseMainWalletBalance("wallet-1", new BigDecimal("275"));
        verify(donationMapper).increasePotBalance("pot-1", new BigDecimal("275"));
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(donationMapper).insertWalletTransaction(captor.capture());
        assertEquals("wallet-1", captor.getValue().get("sourceWalletId"));
        assertEquals("pot-1", captor.getValue().get("counterWalletId"));
        assertEquals(new BigDecimal("275"), captor.getValue().get("amount"));
        assertEquals("spare-trim-member-1-" + today, captor.getValue().get("idempotencyKey"));
        verify(donationMapper).markSpareTrimmed("member-1", today);
    }

    @Test
    @DisplayName("저금통이 아직 없는 회원이면 만든 뒤 잠가서 처리한다")
    void should_createPot_when_potDoesNotExistYet() {
        LocalDate today = LocalDate.now(DonationRoundUpExecutor.SEOUL);
        when(donationMapper.findSettingsForUpdate("member-1")).thenReturn(settings(true, "1000", null));
        when(donationMapper.findMainWalletForUpdate("member-1"))
                .thenReturn(wallet("wallet-1", "31275"));
        when(donationMapper.findPotByMemberId("member-1")).thenReturn(null, wallet("pot-1", "0"));
        when(donationMapper.findPotForUpdate("member-1")).thenReturn(wallet("pot-1", "0"));
        when(donationMapper.decreaseMainWalletBalance(eq("wallet-1"), any())).thenReturn(1);
        when(donationMapper.increasePotBalance(eq("pot-1"), any())).thenReturn(1);
        when(donationMapper.markSpareTrimmed("member-1", today)).thenReturn(1);

        boolean result = executor().execute(candidate("member-1"));

        assertTrue(result);
        verify(donationMapper).insertPot(anyMap());
    }

    @Test
    @DisplayName("잔액이 저금 단위로 딱 나눠떨어지면 표시만 하고 이체하지 않는다")
    void should_markTrimmedWithoutTransfer_when_balanceDividesEvenly() {
        LocalDate today = LocalDate.now(DonationRoundUpExecutor.SEOUL);
        when(donationMapper.findSettingsForUpdate("member-1")).thenReturn(settings(true, "1000", null));
        when(donationMapper.findMainWalletForUpdate("member-1"))
                .thenReturn(wallet("wallet-1", "31000"));
        when(donationMapper.markSpareTrimmed("member-1", today)).thenReturn(1);

        boolean result = executor().execute(candidate("member-1"));

        assertFalse(result);
        verify(donationMapper).markSpareTrimmed("member-1", today);
        verify(donationMapper, never()).decreaseMainWalletBalance(any(), any());
        verify(donationMapper, never()).insertWalletTransaction(anyMap());
    }

    @Test
    @DisplayName("오늘 이미 깎은 회원이면 지갑을 건드리지 않는다")
    void should_skipWithoutTouchingWallets_when_alreadyTrimmedToday() {
        LocalDate today = LocalDate.now(DonationRoundUpExecutor.SEOUL);
        when(donationMapper.findSettingsForUpdate("member-1"))
                .thenReturn(settings(true, "1000", today));

        boolean result = executor().execute(candidate("member-1"));

        assertFalse(result);
        verify(donationMapper, never()).findMainWalletForUpdate(any());
        verify(donationMapper, never()).decreaseMainWalletBalance(any(), any());
        verify(donationMapper, never()).markSpareTrimmed(any(), any());
    }

    @Test
    @DisplayName("저금통 사용이 꺼져 있으면 이체하지 않는다")
    void should_skip_when_piggyBankIsDisabled() {
        when(donationMapper.findSettingsForUpdate("member-1")).thenReturn(settings(false, "1000", null));

        boolean result = executor().execute(candidate("member-1"));

        assertFalse(result);
        verify(donationMapper, never()).findMainWalletForUpdate(any());
    }

    @Test
    @DisplayName("적립 단위가 0 이하면 이체하지 않는다")
    void should_skip_when_savingUnitIsNotPositive() {
        when(donationMapper.findSettingsForUpdate("member-1")).thenReturn(settings(true, "0", null));

        boolean result = executor().execute(candidate("member-1"));

        assertFalse(result);
        verify(donationMapper, never()).findMainWalletForUpdate(any());
    }

    @Test
    @DisplayName("잔액 차감이 실패하면 예외를 던지고 절삭 완료로 넘어가지 않는다")
    void should_throw_andNotMark_when_decreaseMainAffectsNoRows() {
        when(donationMapper.findSettingsForUpdate("member-1")).thenReturn(settings(true, "1000", null));
        when(donationMapper.findMainWalletForUpdate("member-1"))
                .thenReturn(wallet("wallet-1", "31275"));
        when(donationMapper.findPotByMemberId("member-1")).thenReturn(wallet("pot-1", "0"));
        when(donationMapper.findPotForUpdate("member-1")).thenReturn(wallet("pot-1", "0"));
        when(donationMapper.decreaseMainWalletBalance(eq("wallet-1"), any())).thenReturn(0);

        assertThrows(BusinessException.class, () -> executor().execute(candidate("member-1")));

        verify(donationMapper, never()).markSpareTrimmed(any(), any());
        verify(donationMapper, never()).insertWalletTransaction(anyMap());
    }

    @Test
    @DisplayName("31,275원을 1,000·100·10원 단위로 깎으면 나머지 275·75·5원이다")
    void should_returnRemainder_forSavingUnits() {
        assertEquals(new BigDecimal("275"),
                DonationRoundUpExecutor.truncatedRemainder(new BigDecimal("31275"), new BigDecimal("1000")));
        assertEquals(new BigDecimal("75"),
                DonationRoundUpExecutor.truncatedRemainder(new BigDecimal("31275"), new BigDecimal("100")));
        assertEquals(new BigDecimal("5"),
                DonationRoundUpExecutor.truncatedRemainder(new BigDecimal("31275"), new BigDecimal("10")));
        assertEquals(BigDecimal.ZERO,
                DonationRoundUpExecutor.truncatedRemainder(new BigDecimal("31000"), new BigDecimal("1000")));
    }

    private Map<String, Object> candidate(String memberId) {
        Map<String, Object> row = new HashMap<>();
        row.put("memberId", memberId);
        return row;
    }

    private Map<String, Object> settings(boolean enabled, String unit, LocalDate lastTrimmedOn) {
        Map<String, Object> row = new HashMap<>();
        row.put("piggy_bank_enabled", enabled ? 1 : 0);
        row.put("saving_unit", new BigDecimal(unit));
        row.put("last_spare_trimmed_on", lastTrimmedOn);
        return row;
    }

    private Map<String, Object> wallet(String walletId, String balance) {
        Map<String, Object> row = new HashMap<>();
        row.put("wallet_id", walletId);
        row.put("balance", new BigDecimal(balance));
        return row;
    }
}
