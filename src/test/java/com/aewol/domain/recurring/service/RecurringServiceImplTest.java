package com.aewol.domain.recurring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.recurring.dto.RecurringCreateRequest;
import com.aewol.domain.recurring.dto.RecurringResponse;
import com.aewol.domain.recurring.mapper.RecurringMapper;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class RecurringServiceImplTest {

    @Mock RecurringMapper recurringMapper;
    @Mock WalletMapper walletMapper;
    @Mock PetMapper petMapper;
    @Mock TransactionMapper transactionMapper;

    private RecurringServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RecurringServiceImpl(recurringMapper, walletMapper, petMapper, transactionMapper);
        // Mockito는 Map 반환 메서드에 빈 맵을 준다. MyBatis는 없으면 null이라 빈 맵은 미조회로 본다.
        lenient().when(recurringMapper.findByWalletIdAndIdempotencyKey(anyString(), anyString()))
                .thenReturn(null);
    }

    @Test
    void should_returnRecurringPaymentsInApiShape_when_walletExists() {
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet("wallet-1", "member-1"));
        Map<String, Object> row = new HashMap<>();
        row.put("recurring_id", 10L);
        row.put("product_name", "강아지 사료 정기배송");
        row.put("price", new BigDecimal("32000.00"));
        row.put("payment_day", 15);
        row.put("category", "FOOD");
        row.put("pet_id", 3L);
        row.put("next_payment_date", LocalDate.of(2026, 8, 15));
        when(recurringMapper.findByWalletId("wallet-1")).thenReturn(List.of(row));

        List<RecurringResponse> result = service.getRecurringPayments("member-1");

        assertEquals(1, result.size());
        assertEquals("10", result.get(0).getRecurringId());
        assertEquals("강아지 사료 정기배송", result.get(0).getItemName());
        assertEquals(new BigDecimal("32000.00"), result.get(0).getPrice());
        assertEquals(15, result.get(0).getCycleDay());
        assertEquals("2026-08-15", result.get(0).getNextPaymentDate());
    }

    @Test
    void should_throwNotFound_when_walletDoesNotExistForList() {
        when(walletMapper.findByMemberId("member-1")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getRecurringPayments("member-1"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verifyNoInteractions(recurringMapper);
    }

    @Test
    void should_createRecurringPayment_when_requestIsValid() {
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet("wallet-1", "member-1"));
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        stubFirstCharge("wallet-1", new BigDecimal("32000"));
        int cycleDay = 15;
        RecurringCreateRequest request = new RecurringCreateRequest(
                "  강아지 사료 정기배송  ", new BigDecimal("32000"), cycleDay, "FOOD", "pet-1",
                "recurring-key-1");

        RecurringResponse result = service.createRecurring("member-1", request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(recurringMapper).insert(captor.capture());
        assertEquals("wallet-1", captor.getValue().get("walletId"));
        assertEquals("강아지 사료 정기배송", captor.getValue().get("productName"));
        assertEquals("recurring-key-1", captor.getValue().get("idempotencyKey"));
        assertEquals(nextPaymentDateAfterFirstCharge(cycleDay), captor.getValue().get("nextPaymentDate"));
        assertEquals("11", result.getRecurringId());
        assertEquals("pet-1", result.getPetId());
        ArgumentCaptor<Map<String, Object>> txnCaptor = ArgumentCaptor.forClass(Map.class);
        verify(transactionMapper).insert(txnCaptor.capture());
        assertEquals("11", String.valueOf(txnCaptor.getValue().get("recurringId")));
        assertEquals("정기결제", txnCaptor.getValue().get("memo"));
        assertEquals("강아지 사료 정기배송", txnCaptor.getValue().get("merchantName"));
        verify(walletMapper).deductBalance("wallet-1", new BigDecimal("32000"));
    }

    @Test
    void should_createRecurringPaymentWithoutPet_when_petIdIsBlank() {
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet("wallet-1", "member-1"));
        stubFirstCharge("wallet-1", new BigDecimal("26000"));
        RecurringCreateRequest request = new RecurringCreateRequest(
                "펫보험료", new BigDecimal("26000"), 1, "MEDICAL", "  ", "recurring-key-2");

        RecurringResponse result = service.createRecurring("member-1", request);

        assertNull(result.getPetId());
        verifyNoInteractions(petMapper);
        verify(transactionMapper).insert(anyMap());
    }

    @Test
    void should_rejectCreate_when_walletBalanceIsInsufficient() {
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet("wallet-1", "member-1"));
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        when(walletMapper.deductBalance("wallet-1", new BigDecimal("32000"))).thenReturn(0);
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료", new BigDecimal("32000"), 15, "FOOD", "pet-1", "recurring-key-1");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createRecurring("member-1", request));

        assertEquals("잔액이 부족합니다.", exception.getMessage());
        verify(recurringMapper).insert(anyMap());
        verify(transactionMapper, never()).insert(anyMap());
    }

    @Test
    void should_scheduleNextMonthAfterFirstCharge_when_paymentDayIsLaterThisMonth() {
        assertEquals(LocalDate.of(2026, 9, 28),
                RecurringServiceImpl.nextPaymentDateAfterFirstCharge(28, LocalDate.of(2026, 8, 24)));
    }

    @Test
    void should_useLastDayOfFebruary_when_cycleDayDoesNotExistInMonth() {
        assertEquals(LocalDate.of(2027, 2, 28),
                RecurringServiceImpl.nextPaymentDate(31, LocalDate.of(2027, 2, 10)));
        assertEquals(LocalDate.of(2028, 2, 29),
                RecurringServiceImpl.nextPaymentDate(31, LocalDate.of(2028, 2, 10)));
    }

    @Test
    void should_useLastDayOfThirtyDayMonth_when_cycleDayIsThirtyOne() {
        assertEquals(LocalDate.of(2026, 4, 30),
                RecurringServiceImpl.nextPaymentDate(31, LocalDate.of(2026, 4, 10)));
    }

    @Test
    void should_scheduleNextMonth_when_clampedPaymentDateIsToday() {
        assertEquals(LocalDate.of(2026, 5, 31),
                RecurringServiceImpl.nextPaymentDate(31, LocalDate.of(2026, 4, 30)));
    }

    @Test
    void should_throwForbidden_when_memberDoesNotOwnSelectedPet() {
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet("wallet-1", "member-1"));
        when(petMapper.findById("pet-1")).thenReturn(pet("owner-1"));
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료", new BigDecimal("32000"), 15, "FOOD", "pet-1", "recurring-key-1");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createRecurring("member-1", request));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verify(recurringMapper, never()).insert(anyMap());
    }

    @Test
    void should_returnExistingRecurring_when_idempotencyKeyIsReused() {
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet("wallet-1", "member-1"));
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        Map<String, Object> existing = new HashMap<>();
        existing.put("recurring_id", 11L);
        existing.put("product_name", "강아지 사료 정기배송");
        existing.put("price", new BigDecimal("32000.00"));
        existing.put("payment_day", 15);
        existing.put("category", "FOOD");
        existing.put("pet_id", "pet-1");
        existing.put("next_payment_date", LocalDate.of(2026, 9, 15));
        when(recurringMapper.findByWalletIdAndIdempotencyKey("wallet-1", "recurring-key-1"))
                .thenReturn(existing);
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료 정기배송", new BigDecimal("32000"), 15, "FOOD", "pet-1",
                "recurring-key-1");

        RecurringResponse result = service.createRecurring("member-1", request);

        assertEquals("11", result.getRecurringId());
        verify(recurringMapper, never()).insert(anyMap());
        verify(walletMapper, never()).deductBalance(anyString(), any());
        verify(transactionMapper, never()).insert(anyMap());
    }

    @Test
    void should_returnExistingRecurring_when_concurrentInsertHitsUniqueKey() {
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet("wallet-1", "member-1"));
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        Map<String, Object> existing = new HashMap<>();
        existing.put("recurring_id", 11L);
        existing.put("product_name", "강아지 사료");
        existing.put("price", new BigDecimal("32000"));
        existing.put("payment_day", 15);
        existing.put("category", "FOOD");
        existing.put("pet_id", "pet-1");
        existing.put("next_payment_date", LocalDate.of(2026, 9, 15));
        when(recurringMapper.findByWalletIdAndIdempotencyKey("wallet-1", "recurring-key-1"))
                .thenReturn(null)
                .thenReturn(existing);
        doThrow(new DuplicateKeyException("duplicate"))
                .when(recurringMapper).insert(anyMap());
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료", new BigDecimal("32000"), 15, "FOOD", "pet-1", "recurring-key-1");

        RecurringResponse result = service.createRecurring("member-1", request);

        assertEquals("11", result.getRecurringId());
        verify(walletMapper, never()).deductBalance(anyString(), any());
        verify(transactionMapper, never()).insert(anyMap());
    }

    @Test
    void should_throwBadRequest_when_createIdempotencyKeyIsBlank() {
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료", new BigDecimal("32000"), 15, "FOOD", "pet-1", "  ");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createRecurring("member-1", request));

        assertEquals("중복 요청 방지 키를 입력해 주세요.", exception.getMessage());
        verifyNoInteractions(walletMapper, recurringMapper, transactionMapper);
    }

    @Test
    void should_deactivateRecurring_when_memberOwnsWallet() {
        when(recurringMapper.findByIdForUpdate("recurring-1"))
                .thenReturn(Map.of("wallet_id", "wallet-1", "is_active", 1));
        when(walletMapper.findById("wallet-1")).thenReturn(wallet("wallet-1", "member-1"));
        when(recurringMapper.deactivate("recurring-1")).thenReturn(1);

        service.cancelRecurring("member-1", "recurring-1");

        verify(recurringMapper).deactivate("recurring-1");
    }

    @Test
    void should_throwNotFound_when_recurringDoesNotExist() {
        when(recurringMapper.findByIdForUpdate("recurring-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.cancelRecurring("member-1", "recurring-404"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void should_throwForbidden_when_memberDoesNotOwnRecurringWallet() {
        when(recurringMapper.findByIdForUpdate("recurring-1"))
                .thenReturn(Map.of("wallet_id", "wallet-1", "is_active", 1));
        when(walletMapper.findById("wallet-1")).thenReturn(wallet("wallet-1", "owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.cancelRecurring("member-2", "recurring-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verify(recurringMapper, never()).deactivate(anyString());
    }

    @Test
    void should_updateRecurringPayment_when_memberOwnsWalletAndRequestIsValid() {
        when(recurringMapper.findByIdForUpdate("recurring-1")).thenReturn(recurring("wallet-1", 15,
                LocalDate.now().withDayOfMonth(Math.min(15, LocalDate.now().lengthOfMonth()))));
        when(walletMapper.findById("wallet-1")).thenReturn(wallet("wallet-1", "member-1"));
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
        when(recurringMapper.update(anyMap())).thenReturn(1);
        int cycleDay = 20;
        RecurringCreateRequest request = new RecurringCreateRequest(
                "  변경된 사료 정기배송  ", new BigDecimal("40000"), cycleDay, "FOOD", "pet-1", null);

        RecurringResponse result = service.updateRecurring("member-1", "recurring-1", request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(recurringMapper).update(captor.capture());
        assertEquals("recurring-1", captor.getValue().get("recurringId"));
        assertEquals("변경된 사료 정기배송", captor.getValue().get("productName"));
        assertEquals(cycleDay, captor.getValue().get("paymentDay"));
        assertEquals(nextPaymentDate(cycleDay), captor.getValue().get("nextPaymentDate"));
        assertEquals("recurring-1", result.getRecurringId());
        assertEquals(20, result.getCycleDay());
        assertEquals("pet-1", result.getPetId());
    }

    @Test
    void should_keepCurrentPaymentDate_when_updatingOtherFieldsOnPaymentDay() {
        LocalDate today = LocalDate.now();
        int cycleDay = today.getDayOfMonth();
        when(recurringMapper.findByIdForUpdate("recurring-1"))
                .thenReturn(recurring("wallet-1", cycleDay, today));
        when(walletMapper.findById("wallet-1")).thenReturn(wallet("wallet-1", "member-1"));
        when(recurringMapper.update(anyMap())).thenReturn(1);
        RecurringCreateRequest request = new RecurringCreateRequest(
                "변경된 상품명", new BigDecimal("40000"), cycleDay, "FOOD", null, null);

        RecurringResponse result = service.updateRecurring("member-1", "recurring-1", request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(recurringMapper).update(captor.capture());
        assertEquals(today, captor.getValue().get("nextPaymentDate"));
        assertEquals(today.toString(), result.getNextPaymentDate());
    }

    @Test
    void should_throwForbidden_when_memberDoesNotOwnRecurringWalletForUpdate() {
        when(recurringMapper.findByIdForUpdate("recurring-1"))
                .thenReturn(Map.of("wallet_id", "wallet-1", "is_active", 1));
        when(walletMapper.findById("wallet-1")).thenReturn(wallet("wallet-1", "owner-1"));
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료", new BigDecimal("32000"), 15, "FOOD", null, null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateRecurring("member-2", "recurring-1", request));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verify(recurringMapper, never()).update(anyMap());
    }

    @Test
    void should_throwNotFound_when_recurringDoesNotExistForUpdate() {
        when(recurringMapper.findByIdForUpdate("recurring-404")).thenReturn(null);
        RecurringCreateRequest request = new RecurringCreateRequest(
                "강아지 사료", new BigDecimal("32000"), 15, "FOOD", null, null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateRecurring("member-1", "recurring-404", request));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verify(recurringMapper, never()).update(anyMap());
    }

    private static LocalDate nextPaymentDate(int cycleDay) {
        LocalDate today = LocalDate.now();
        LocalDate candidate = today.withDayOfMonth(Math.min(cycleDay, today.lengthOfMonth()));
        return candidate.isAfter(today) ? candidate : candidate.plusMonths(1);
    }

    private static LocalDate nextPaymentDateAfterFirstCharge(int cycleDay) {
        return RecurringServiceImpl.nextPaymentDateAfterFirstCharge(cycleDay, LocalDate.now());
    }

    private void stubFirstCharge(String walletId, BigDecimal price) {
        when(walletMapper.deductBalance(walletId, price)).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<Map<String, Object>>getArgument(0).put("recurringId", 11L);
            return null;
        }).when(recurringMapper).insert(anyMap());
    }

    private static Map<String, Object> wallet(String walletId, String memberId) {
        return Map.of("wallet_id", walletId, "member_id", memberId);
    }

    private static Map<String, Object> recurring(
            String walletId, int paymentDay, LocalDate nextPaymentDate) {
        return Map.of(
                "wallet_id", walletId,
                "is_active", 1,
                "payment_day", paymentDay,
                "next_payment_date", nextPaymentDate);
    }

    private static Map<String, Object> pet(String memberId) {
        return Map.of("pet_id", "pet-1", "member_id", memberId);
    }
}
