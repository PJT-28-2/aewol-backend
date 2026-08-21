package com.aewol.domain.transaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

import com.aewol.domain.transaction.dto.PaymentRecordCommand;
import com.aewol.domain.transaction.dto.PaymentRequest;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.transaction.dto.TransactionTagUpdateRequest;
import com.aewol.common.exception.BusinessException;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class TransactionServiceImplTest {

    @Mock TransactionMapper transactionMapper;
    @Mock WalletMapper walletMapper;
    @Mock AutoTaggingService autoTaggingService;
    @Mock PetMapper petMapper;

    @Test
    @DisplayName("음수 결제 금액은 지갑 조회 전에 거절한다")
    void should_rejectPaymentBeforeWalletLookup_when_amountIsNegative() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().processPayment("member-1", paymentRequest("애월동물병원", new BigDecimal("-1000"))));

        assertEquals("결제 금액은 1원 이상이어야 합니다.", exception.getMessage());
        verifyNoInteractions(walletMapper, transactionMapper, autoTaggingService, petMapper);
    }

    @Test
    @DisplayName("0원 결제는 지갑 조회 전에 거절한다")
    void should_rejectPaymentBeforeWalletLookup_when_amountIsZero() {
        assertThrows(BusinessException.class,
                () -> service().processPayment("member-1", paymentRequest("애월동물병원", BigDecimal.ZERO)));

        verifyNoInteractions(walletMapper, transactionMapper, autoTaggingService, petMapper);
    }

    @Test
    @DisplayName("소수 결제 금액은 지갑 조회 전에 거절한다")
    void should_rejectPaymentBeforeWalletLookup_when_amountHasFraction() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().processPayment("member-1", paymentRequest("애월동물병원", new BigDecimal("1000.5"))));

        assertEquals("결제 금액은 1원 단위여야 합니다.", exception.getMessage());
        verifyNoInteractions(walletMapper, transactionMapper, autoTaggingService, petMapper);
    }

    @Test
    @DisplayName("정수부 13자리를 초과한 결제 금액은 지갑 조회 전에 거절한다")
    void should_rejectPaymentBeforeWalletLookup_when_amountExceedsDatabaseRange() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().processPayment("member-1",
                        paymentRequest("애월동물병원", new BigDecimal("10000000000000"))));

        assertEquals("결제 금액은 정수부 13자리 이하만 입력할 수 있습니다.", exception.getMessage());
        verifyNoInteractions(walletMapper, transactionMapper, autoTaggingService, petMapper);
    }

    @Test
    @DisplayName("100자를 초과한 가맹점명은 지갑 조회 전에 거절한다")
    void should_rejectPaymentBeforeWalletLookup_when_merchantNameIsTooLong() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().processPayment("member-1", paymentRequest("가".repeat(101), BigDecimal.ONE)));

        assertEquals("가맹점명은 100자 이하만 입력할 수 있습니다.", exception.getMessage());
        verifyNoInteractions(walletMapper, transactionMapper, autoTaggingService, petMapper);
    }

    @Test
    @DisplayName("반려동물을 선택해 결제하면 거래에 반려동물 태그를 직접 저장한다")
    void should_savePetId_when_paymentHasSelectedPet() {
        TransactionServiceImpl service = new TransactionServiceImpl(
                transactionMapper, walletMapper, autoTaggingService, petMapper);
        PaymentRequest request = new PaymentRequest();
        ReflectionTestUtils.setField(request, "merchantName", "애월동물병원");
        ReflectionTestUtils.setField(request, "amount", new BigDecimal("72000"));
        ReflectionTestUtils.setField(request, "petId", "pet-1");
        when(walletMapper.findByMemberId("member-1")).thenReturn(map(
                "wallet_id", "wallet-1", "balance", new BigDecimal("100000")));
        when(walletMapper.findById("wallet-1")).thenReturn(map("member_id", "member-1"));
        when(walletMapper.deductBalance("wallet-1", new BigDecimal("72000"))).thenReturn(1);
        when(autoTaggingService.categorize("애월동물병원")).thenReturn("HOSPITAL");
        when(transactionMapper.findById(any())).thenAnswer(invocation -> map(
                "txn_id", 1L, "wallet_id", "wallet-1",
                "pet_id", "pet-1", "txn_type", "PAYMENT",
                "price", new BigDecimal("72000"), "category", "HOSPITAL",
                "merchant_name", "애월동물병원", "auto_tagged", "Y",
                "txn_date", LocalDateTime.now()));

        service.processPayment("member-1", request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(transactionMapper).insert(captor.capture());
        assertEquals("pet-1", captor.getValue().get("petId"));
        assertEquals("애월동물병원", captor.getValue().get("merchantName"));
        assertEquals(null, captor.getValue().get("memo"));
        assertEquals("HOSPITAL", captor.getValue().get("category"));
        assertEquals(new BigDecimal("72000"), captor.getValue().get("price"));
        assertEquals("PAYMENT", captor.getValue().get("txnType"));
        assertEquals("wallet-1", captor.getValue().get("walletId"));
        assertEquals("Y", captor.getValue().get("autoTagged"));
    }

    @Test
    @DisplayName("잔액이 부족하면 결제 없이 예외가 발생한다")
    void should_throwException_when_balanceInsufficient() {
        TransactionServiceImpl service = service();
        PaymentRequest request = new PaymentRequest();
        ReflectionTestUtils.setField(request, "merchantName", "애월동물병원");
        ReflectionTestUtils.setField(request, "amount", new BigDecimal("72000"));
        when(walletMapper.findByMemberId("member-1")).thenReturn(map(
                "wallet_id", "wallet-1", "balance", new BigDecimal("10000")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.processPayment("member-1", request));

        assertEquals("잔액이 부족합니다.", exception.getMessage());
        verify(walletMapper, never()).deductBalance(any(), any());
        verifyNoInteractions(transactionMapper);
    }

    @Test
    @DisplayName("사전 잔액 확인은 통과했지만 동시 결제로 원자적 차감이 실패하면(영향 행 0) 잔액 부족으로 처리한다")
    void should_throwException_when_deductBalanceAffectsNoRows() {
        TransactionServiceImpl service = service();
        PaymentRequest request = new PaymentRequest();
        ReflectionTestUtils.setField(request, "merchantName", "애월동물병원");
        ReflectionTestUtils.setField(request, "amount", new BigDecimal("72000"));
        when(walletMapper.findByMemberId("member-1")).thenReturn(map(
                "wallet_id", "wallet-1", "balance", new BigDecimal("100000")));
        when(walletMapper.deductBalance("wallet-1", new BigDecimal("72000"))).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.processPayment("member-1", request));

        assertEquals("잔액이 부족합니다.", exception.getMessage());
        verifyNoInteractions(transactionMapper);
    }

    @Test
    void should_returnTransaction_when_memberOwnsWallet() {
        TransactionServiceImpl service = service();
        when(transactionMapper.findById("txn-1")).thenReturn(transaction("wallet-1", null));
        when(walletMapper.findById("wallet-1")).thenReturn(map("member_id", "member-1"));

        assertEquals("txn-1", service.getTransaction("member-1", "txn-1").getTxnId());
    }

    @Test
    void should_throwNotFound_when_transactionDoesNotExist() {
        TransactionServiceImpl service = service();
        when(transactionMapper.findById("txn-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getTransaction("member-1", "txn-404"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void should_throwForbidden_when_memberCannotViewTransaction() {
        TransactionServiceImpl service = service();
        when(transactionMapper.findById("txn-1")).thenReturn(transaction("wallet-1", "pet-1"));
        when(walletMapper.findById("wallet-1")).thenReturn(map("member_id", "owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getTransaction("member-2", "txn-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    @Test
    void should_throwForbidden_when_nonOwnerViewsTransactionWithoutPet() {
        TransactionServiceImpl service = service();
        when(transactionMapper.findById("txn-1")).thenReturn(transaction("wallet-1", null));
        when(walletMapper.findById("wallet-1")).thenReturn(map("member_id", "owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getTransaction("member-2", "txn-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    @Test
    void should_returnRecentTransactions_when_memberHasWallet() {
        TransactionServiceImpl service = service();
        when(walletMapper.findByMemberId("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(transactionMapper.findRecentByWalletId("wallet-1", null, 4)).thenReturn(List.of(
                transaction("wallet-1", null),
                map("txn_id", "txn-2", "wallet_id", "wallet-1", "txn_type", "PAYMENT",
                        "price", BigDecimal.ONE, "auto_tagged", "N", "txn_date", LocalDateTime.now())));

        List<?> result = service.getRecentTransactions("member-1", "ALL", 4);

        assertEquals(2, result.size());
        verify(transactionMapper).findRecentByWalletId("wallet-1", null, 4);
    }

    @Test
    void should_returnEmptyList_when_memberHasNoTransactions() {
        TransactionServiceImpl service = service();
        when(walletMapper.findByMemberId("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(transactionMapper.findRecentByWalletId("wallet-1", null, 4)).thenReturn(List.of());

        assertEquals(List.of(), service.getRecentTransactions("member-1", "ALL", 4));
    }

    @Test
    void should_throwNotFound_when_memberHasNoWalletForRecentTransactions() {
        TransactionServiceImpl service = service();
        when(walletMapper.findByMemberId("member-1")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getRecentTransactions("member-1", "ALL", 4));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verifyNoInteractions(transactionMapper);
    }

    @Test
    void should_limitRecentTransactions_when_limitIsValid() {
        TransactionServiceImpl service = service();
        when(walletMapper.findByMemberId("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(transactionMapper.findRecentByWalletId("wallet-1", "CHARGE", 3)).thenReturn(List.of());

        service.getRecentTransactions("member-1", "CHARGE", 3);

        verify(transactionMapper).findRecentByWalletId("wallet-1", "CHARGE", 3);
    }

    @Test
    void should_throwException_when_recentLimitExceedsMaximum() {
        TransactionServiceImpl service = service();

        assertThrows(BusinessException.class,
                () -> service.getRecentTransactions("member-1", "ALL", 21));
        verifyNoInteractions(walletMapper, transactionMapper);
    }

    @Test
    void should_updateTag_when_memberOwnsPaymentTransaction() {
        TransactionServiceImpl service = service();
        TransactionTagUpdateRequest request = new TransactionTagUpdateRequest();
        ReflectionTestUtils.setField(request, "category", "FOOD");
        ReflectionTestUtils.setField(request, "petId", "pet-2");
        when(transactionMapper.findById("txn-1"))
                .thenReturn(transaction("wallet-1", "pet-1"), transaction("wallet-1", "pet-2"));
        when(walletMapper.findById("wallet-1")).thenReturn(map("member_id", "member-1"));
        when(petMapper.findByIdAndMemberId("pet-2", "member-1"))
                .thenReturn(map("pet_id", "pet-2"));
        when(transactionMapper.updateTag("txn-1", "FOOD", "pet-2")).thenReturn(1);

        service.updateTag("member-1", "txn-1", request);

        verify(transactionMapper).updateTag("txn-1", "FOOD", "pet-2");
    }

    @Test
    void should_returnCursorPage_when_moreTransactionsExist() {
        TransactionServiceImpl service = service();
        when(walletMapper.findByMemberId("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(transactionMapper.findByWalletId(
                eq("wallet-1"), eq("ALL"), any(LocalDateTime.class),
                any(LocalDateTime.class), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(
                        map("txn_id", 3L, "wallet_id", "wallet-1", "txn_type", "PAYMENT", "price", BigDecimal.ONE,
                                "txn_date", LocalDateTime.of(2026, 8, 7, 14, 13, 21)),
                        map("txn_id", 2L, "wallet_id", "wallet-1", "txn_type", "PAYMENT", "price", BigDecimal.ONE,
                                "txn_date", LocalDateTime.of(2026, 8, 7, 14, 13, 17)),
                        map("txn_id", 1L, "wallet_id", "wallet-1", "txn_type", "PAYMENT", "price", BigDecimal.ONE,
                                "txn_date", LocalDateTime.of(2026, 8, 7, 14, 13, 12))));

        var result = service.getTransactions("member-1", "ALL", "2026-08", null, 2);

        assertEquals(2, result.getTransactions().size());
        assertNotNull(result.getNextCursor());
    }

    @Test
    void should_useSameWithdrawFilterForRecentTransactions() {
        TransactionServiceImpl service = service();
        when(walletMapper.findByMemberId("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(transactionMapper.findRecentByWalletId("wallet-1", "WITHDRAW", 4)).thenReturn(List.of());

        service.getRecentTransactions("member-1", "WITHDRAW", 4);

        verify(transactionMapper).findRecentByWalletId("wallet-1", "WITHDRAW", 4);
    }

    @Test
    @DisplayName("최근 거래 조회에서도 type=PAYMENT을 매퍼에 그대로 전달한다")
    void should_passPaymentFilter_forRecentTransactions() {
        TransactionServiceImpl service = service();
        when(walletMapper.findByMemberId("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(transactionMapper.findRecentByWalletId("wallet-1", "PAYMENT", 4)).thenReturn(List.of());

        service.getRecentTransactions("member-1", "PAYMENT", 4);

        verify(transactionMapper).findRecentByWalletId("wallet-1", "PAYMENT", 4);
    }

    @Test
    void should_throwException_when_recentTransactionTypeIsUnknown() {
        TransactionServiceImpl service = service();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getRecentTransactions("member-1", "REFUND", 4));

        assertEquals("거래 유형은 ALL, CHARGE, WITHDRAW, PAYMENT 중 하나여야 합니다.", exception.getMessage());
        verifyNoInteractions(walletMapper, transactionMapper);
    }

    @Test
    @DisplayName("type=PAYMENT을 넘기면 매퍼에 PAYMENT 필터로 그대로 전달된다")
    void should_passPaymentFilter_when_typeIsPayment() {
        TransactionServiceImpl service = service();
        when(walletMapper.findByMemberId("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(transactionMapper.findByWalletId(
                eq("wallet-1"), eq("PAYMENT"), any(LocalDateTime.class),
                any(LocalDateTime.class), isNull(), isNull(), eq(21)))
                .thenReturn(List.of());

        service.getTransactions("member-1", "PAYMENT", "2026-08", null, 20);

        verify(transactionMapper).findByWalletId(
                eq("wallet-1"), eq("PAYMENT"), any(LocalDateTime.class),
                any(LocalDateTime.class), isNull(), isNull(), eq(21));
    }

    @Test
    void should_throwException_when_transactionTypeIsUnknown() {
        TransactionServiceImpl service = service();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getTransactions("member-1", "REFUND", "2026-08", null, 20));

        assertEquals("거래 유형은 ALL, CHARGE, WITHDRAW, PAYMENT 중 하나여야 합니다.", exception.getMessage());
        verifyNoInteractions(walletMapper, transactionMapper);
    }

    @Test
    void should_throwException_when_cursorIsInvalid() {
        TransactionServiceImpl service = service();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getTransactions("member-1", "ALL", "2026-08", "wrong-cursor", 20));

        assertEquals("유효하지 않은 거래 커서입니다.", exception.getMessage());
        verifyNoInteractions(walletMapper, transactionMapper);
    }

    @Test
    void should_throwNotFound_when_tagPetDoesNotBelongToMember() {
        TransactionServiceImpl service = service();
        TransactionTagUpdateRequest request = new TransactionTagUpdateRequest();
        ReflectionTestUtils.setField(request, "category", "FOOD");
        ReflectionTestUtils.setField(request, "petId", "pet-2");
        when(transactionMapper.findById("txn-1")).thenReturn(transaction("wallet-1", "pet-1"));
        when(walletMapper.findById("wallet-1")).thenReturn(map("member_id", "member-1"));
        when(petMapper.findByIdAndMemberId("pet-2", "member-1")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateTag("member-1", "txn-1", request));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verify(transactionMapper, never()).updateTag(any(), any(), any());
    }

    @Test
    @DisplayName("지갑 결제 경로는 공유 insert 문만 사용한다(충전 전용 insertTossPayment를 타지 않는다)")
    void should_useSharedInsert_when_processingLegacyPayment() {
        TransactionServiceImpl service = service();
        PaymentRequest request = new PaymentRequest();
        ReflectionTestUtils.setField(request, "merchantName", "애월동물병원");
        ReflectionTestUtils.setField(request, "amount", new BigDecimal("72000"));
        when(walletMapper.findByMemberId("member-1")).thenReturn(map(
                "wallet_id", "wallet-1", "balance", new BigDecimal("100000")));
        when(walletMapper.findById("wallet-1")).thenReturn(map("member_id", "member-1"));
        when(walletMapper.deductBalance("wallet-1", new BigDecimal("72000"))).thenReturn(1);
        when(autoTaggingService.categorize("애월동물병원")).thenReturn("HOSPITAL");
        when(transactionMapper.findById(any())).thenAnswer(invocation -> map(
                "txn_id", 1L, "wallet_id", "wallet-1", "txn_type", "PAYMENT",
                "price", new BigDecimal("72000"), "category", "HOSPITAL",
                "merchant_name", "애월동물병원", "auto_tagged", "Y",
                "txn_date", LocalDateTime.now()));

        service.processPayment("member-1", request);

        verify(transactionMapper).insert(any());
        verify(transactionMapper, never()).insertTossPayment(any());
    }

    @Test
    @DisplayName("결제 기록은 본인 반려동물만 petId로 남긴다")
    void should_rejectPayment_whenPetDoesNotBelongToMember() {
        TransactionServiceImpl service = service();
        PaymentRequest request = new PaymentRequest();
        ReflectionTestUtils.setField(request, "merchantName", "애월동물병원");
        ReflectionTestUtils.setField(request, "amount", new BigDecimal("72000"));
        ReflectionTestUtils.setField(request, "petId", "pet-2");
        when(walletMapper.findByMemberId("member-1")).thenReturn(map(
                "wallet_id", "wallet-1", "balance", new BigDecimal("100000")));
        when(petMapper.findByIdAndMemberId("pet-2", "member-1")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.processPayment("member-1", request));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verify(walletMapper, never()).deductBalance(any(), any());
        verify(transactionMapper, never()).insert(any());
    }

    private TransactionServiceImpl service() {
        return new TransactionServiceImpl(transactionMapper, walletMapper, autoTaggingService, petMapper);
    }

    private static PaymentRequest paymentRequest(String merchantName, BigDecimal amount) {
        PaymentRequest request = new PaymentRequest();
        ReflectionTestUtils.setField(request, "merchantName", merchantName);
        ReflectionTestUtils.setField(request, "amount", amount);
        return request;
    }

    private static Map<String, Object> transaction(String walletId, String petId) {
        return map("txn_id", "txn-1", "wallet_id", walletId, "pet_id", petId,
                "txn_type", "PAYMENT", "price", BigDecimal.TEN,
                "auto_tagged", "N", "txn_date", LocalDateTime.now());
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
