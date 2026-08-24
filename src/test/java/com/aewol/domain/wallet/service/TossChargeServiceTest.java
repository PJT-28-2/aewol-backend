package com.aewol.domain.wallet.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.dto.ExternalChargeCommand;
import com.aewol.domain.wallet.dto.TossChargeRequest;
import com.aewol.domain.wallet.dto.WalletResponse;
import com.aewol.domain.wallet.mapper.TossChargeOrderMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.tosspayments.TossCancelResult;
import com.aewol.external.tosspayments.TossConfirmOutcome;
import com.aewol.external.tosspayments.TossConfirmResult;
import com.aewol.external.tosspayments.TossPaymentAuditLogger;
import com.aewol.external.tosspayments.TossPaymentClaim;
import com.aewol.external.tosspayments.TossPaymentsClient;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TossChargeServiceTest {

    private static final String MEMBER_ID = "9001";
    private static final long MEMBER_ID_VALUE = 9001L;
    private static final String PAYMENT_KEY = "toss-pay-key-001";
    private static final String ORDER_ID = "order-abc123";

    @Mock TossPaymentClaim tossPaymentClaim;
    @Mock TossPaymentsClient tossPaymentsClient;
    @Mock TossPaymentAuditLogger auditLogger;
    @Mock WalletService walletService;
    @Mock WalletMapper walletMapper;
    @Mock TossChargeOrderMapper tossChargeOrderMapper;
    @Mock TransactionMapper transactionMapper;

    private TossChargeService service;

    private void init() {
        service = new TossChargeService(tossPaymentClaim, tossPaymentsClient, auditLogger,
                walletService, walletMapper, tossChargeOrderMapper, transactionMapper);
    }

    // ── 기존 charge() 시나리오 ─────────────────────────────────────────────────

    @Test
    void should_creditOrderedAmount_when_tossTotalAmountMatches() {
        init();
        stubOrderFound();
        stubWalletFound();
        TossConfirmResult confirmResult = TossConfirmResult.success(10000L, Map.of("status", "DONE"));
        when(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, 10000L)).thenReturn(confirmResult);
        WalletResponse expectedResponse = WalletResponse.builder()
                .walletId("1").memberId(MEMBER_ID).totalBalance(new BigDecimal("10000")).build();
        when(walletService.depositExternal(any())).thenReturn(expectedResponse);

        WalletResponse actual = service.charge(MEMBER_ID, request(new BigDecimal("10000")));

        assertSame(expectedResponse, actual);
        ArgumentCaptor<ExternalChargeCommand> captor = ArgumentCaptor.forClass(ExternalChargeCommand.class);
        verify(walletService).depositExternal(captor.capture());
        assertEquals(new BigDecimal("10000"), captor.getValue().getAmount());
        verify(tossPaymentsClient, never()).cancelPayment(anyString(), anyString());
        verify(tossPaymentClaim, never()).release(anyString());
    }

    @Test
    void should_cancelAndSkipDeposit_when_tossTotalAmountDiffersFromOrder() {
        init();
        stubOrderFound();
        stubWalletFound();
        // 10,000원 주문인데 Toss가 9,500원만 승인하면 원장에 넣지 않고 취소한다.
        TossConfirmResult confirmResult = TossConfirmResult.success(9500L, Map.of("status", "DONE"));
        when(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, 10000L)).thenReturn(confirmResult);
        when(tossPaymentsClient.cancelPayment(eq(PAYMENT_KEY), anyString()))
                .thenReturn(TossCancelResult.success());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("10000"))));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("승인 금액이 주문 금액과 일치하지 않습니다.", ex.getMessage());
        verify(walletService, never()).depositExternal(any());
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(tossPaymentsClient).cancelPayment(eq(PAYMENT_KEY), reasonCaptor.capture());
        assertEquals("승인 금액이 주문 금액과 일치하지 않아 자동 취소", reasonCaptor.getValue());
        verify(auditLogger).compensated();
        verify(tossPaymentClaim).release(ORDER_ID);
    }

    @Test
    void should_keepClaim_when_amountMismatchCancelFails() {
        init();
        stubOrderFound();
        stubWalletFound();
        when(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, 10000L))
                .thenReturn(TossConfirmResult.success(9500L, Map.of("status", "DONE")));
        when(tossPaymentsClient.cancelPayment(eq(PAYMENT_KEY), anyString()))
                .thenReturn(TossCancelResult.failure("FORBIDDEN_CONSECUTIVE_TRANSACTION", "취소 실패"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("10000"))));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        verify(walletService, never()).depositExternal(any());
        verify(auditLogger).compensationFailed();
        verify(tossPaymentClaim, never()).release(anyString());
    }

    @Test
    void should_notCallTossAndReleaseClaim_when_walletNotFound() {
        init();
        stubOrderFound();
        when(walletMapper.findByMemberId(MEMBER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("10000"))));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(tossPaymentsClient, never()).confirmPayment(anyString(), anyString(), anyLong());
        verify(tossPaymentClaim).release(ORDER_ID);
    }

    @Test
    void should_releaseClaimAndSkipAudit_when_definitiveRejection() {
        init();
        stubOrderFound();
        stubWalletFound();
        when(tossPaymentsClient.confirmPayment(anyString(), anyString(), anyLong()))
                .thenReturn(TossConfirmResult.of(TossConfirmOutcome.DEFINITIVE_REJECTION,
                        "REJECT_CARD_COMPANY", "카드사 거절", null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("10000"))));

        assertEquals(HttpStatus.PAYMENT_REQUIRED, ex.getStatus());
        verify(tossPaymentClaim).release(ORDER_ID);
        verify(walletService, never()).depositExternal(any());
        verifyNoInteractions(auditLogger);
    }

    @Test
    void should_keepClaimAndAuditIndeterminate_when_confirmResultIsIndeterminate() {
        init();
        stubOrderFound();
        stubWalletFound();
        when(tossPaymentsClient.confirmPayment(anyString(), anyString(), anyLong()))
                .thenReturn(TossConfirmResult.of(TossConfirmOutcome.INDETERMINATE,
                        "PROVIDER_ERROR", "확인 불가", null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("10000"))));

        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        verify(tossPaymentClaim, never()).release(anyString());
        verify(auditLogger).confirmIndeterminate();
    }

    @Test
    void should_keepClaimAndAuditAlreadyApproved_when_confirmResultIsAlreadyApproved() {
        init();
        stubOrderFound();
        stubWalletFound();
        when(tossPaymentsClient.confirmPayment(anyString(), anyString(), anyLong()))
                .thenReturn(TossConfirmResult.of(TossConfirmOutcome.ALREADY_APPROVED,
                        "ALREADY_PROCESSED_PAYMENT", "이미 처리됨", null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("10000"))));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(tossPaymentClaim, never()).release(anyString());
        verify(auditLogger).alreadyApproved();
    }

    @Test
    void should_compensateWithFixedReasonNotCauseMessage_when_ledgerWriteFailsAfterConfirmSuccess() {
        init();
        stubOrderFound();
        stubWalletFound();
        when(tossPaymentsClient.confirmPayment(anyString(), anyString(), anyLong()))
                .thenReturn(TossConfirmResult.success(10000L, null));
        RuntimeException cause = new RuntimeException(
                "com.mysql.cj.jdbc.exceptions.MySQLSyntaxErrorException: near 'SELECT secret_column FROM wallet'");
        when(walletService.depositExternal(any())).thenThrow(cause);
        when(tossPaymentsClient.cancelPayment(eq(PAYMENT_KEY), anyString()))
                .thenReturn(TossCancelResult.success());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("10000"))));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(tossPaymentsClient).cancelPayment(eq(PAYMENT_KEY), reasonCaptor.capture());
        String cancelReason = reasonCaptor.getValue();
        assertEquals("지갑 충전 기록 실패로 인한 자동 취소", cancelReason);
        assertFalse(cancelReason.contains("MySQLSyntaxErrorException"));
        assertFalse(cancelReason.contains("secret_column"));
        verify(auditLogger).compensated();
    }

    @Test
    void should_auditCompensationFailure_when_cancelAlsoFailsAfterLedgerWriteFails() {
        init();
        stubOrderFound();
        stubWalletFound();
        when(tossPaymentsClient.confirmPayment(anyString(), anyString(), anyLong()))
                .thenReturn(TossConfirmResult.success(10000L, null));
        RuntimeException cause = new RuntimeException("ledger insert failed");
        when(walletService.depositExternal(any())).thenThrow(cause);
        when(tossPaymentsClient.cancelPayment(eq(PAYMENT_KEY), anyString()))
                .thenReturn(TossCancelResult.failure("FORBIDDEN_CONSECUTIVE_TRANSACTION", "취소 실패"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("10000"))));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        verify(auditLogger).compensationFailed();
    }

    @Test
    void should_propagateConflictAndNeverCallToss_when_claimIsContended() {
        init();
        doThrow(new BusinessException(HttpStatus.CONFLICT, "이미 처리 중이거나 최근에 처리된 결제 요청입니다."))
                .when(tossPaymentClaim).acquire(ORDER_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("10000"))));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(tossPaymentsClient, never()).confirmPayment(anyString(), anyString(), anyLong());
    }

    @Test
    void should_notCancelPayment_when_ledgerWriteFailsWithDuplicateKey() {
        init();
        stubOrderFound();
        stubWalletFound();
        when(tossPaymentsClient.confirmPayment(anyString(), anyString(), anyLong()))
                .thenReturn(TossConfirmResult.success(10000L, null));
        when(walletService.depositExternal(any()))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'payment_key'"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("10000"))));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(tossPaymentsClient, never()).cancelPayment(anyString(), anyString());
    }

    @Test
    void should_releaseClaimAndNotReportAsPaymentRequired_when_configError() {
        init();
        stubOrderFound();
        stubWalletFound();
        when(tossPaymentsClient.confirmPayment(anyString(), anyString(), anyLong()))
                .thenReturn(TossConfirmResult.of(TossConfirmOutcome.CONFIG_ERROR,
                        "INVALID_API_KEY", "잘못된 시크릿 키", null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("10000"))));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        assertNotEquals(HttpStatus.PAYMENT_REQUIRED, ex.getStatus());
        verify(tossPaymentClaim).release(ORDER_ID);
    }

    // ── 주문 소유권 검증 시나리오 ──────────────────────────────────────────────

    @Test
    void should_throwNotFoundAndReleaseClaim_when_orderDoesNotExist() {
        init();
        when(tossChargeOrderMapper.findByOrderId(ORDER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("10000"))));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(tossPaymentsClient, never()).confirmPayment(anyString(), anyString(), anyLong());
        verify(tossPaymentClaim).release(ORDER_ID);
    }

    @Test
    void should_throwForbiddenAndReleaseClaim_when_orderBelongsToAnotherMember() {
        init();
        Map<String, Object> order = new HashMap<>();
        order.put("order_id", ORDER_ID);
        order.put("member_id", 9002L);
        order.put("amount", new BigDecimal("10000"));
        order.put("status", "PENDING");
        when(tossChargeOrderMapper.findByOrderId(ORDER_ID)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("10000"))));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(tossPaymentsClient, never()).confirmPayment(anyString(), anyString(), anyLong());
        verify(tossPaymentClaim).release(ORDER_ID);
    }

    @Test
    void should_throwBadRequestAndReleaseClaim_when_requestAmountDiffersFromOrderAmount() {
        init();
        Map<String, Object> order = new HashMap<>();
        order.put("order_id", ORDER_ID);
        order.put("member_id", MEMBER_ID_VALUE);
        order.put("amount", new BigDecimal("10000"));
        order.put("status", "PENDING");
        when(tossChargeOrderMapper.findByOrderId(ORDER_ID)).thenReturn(order);

        // 요청 금액(20000)이 주문 금액(10000)과 다르다.
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("20000"))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(tossPaymentsClient, never()).confirmPayment(anyString(), anyString(), anyLong());
        verify(tossPaymentClaim).release(ORDER_ID);
    }

    @Test
    void should_returnCurrentWalletAndReleaseClaim_when_orderAlreadyApproved() {
        init();
        Map<String, Object> order = new HashMap<>();
        order.put("order_id", ORDER_ID);
        order.put("member_id", MEMBER_ID_VALUE);
        order.put("amount", new BigDecimal("10000"));
        order.put("status", "APPROVED");
        when(tossChargeOrderMapper.findByOrderId(ORDER_ID)).thenReturn(order);
        stubCompletedCharge(PAYMENT_KEY, new BigDecimal("10000"));
        WalletResponse expected = WalletResponse.builder()
                .walletId("1").memberId(MEMBER_ID).totalBalance(new BigDecimal("30000")).build();
        when(walletService.getWallet(MEMBER_ID)).thenReturn(expected);

        WalletResponse actual = service.charge(MEMBER_ID, request(new BigDecimal("10000")));

        assertSame(expected, actual);
        verify(tossPaymentsClient, never()).confirmPayment(anyString(), anyString(), anyLong());
        verify(walletService, never()).depositExternal(any());
        verify(tossPaymentClaim).release(ORDER_ID);
    }

    @Test
    void should_rejectDifferentAmountAndReleaseClaim_when_orderAlreadyApproved() {
        init();
        Map<String, Object> order = new HashMap<>();
        order.put("order_id", ORDER_ID);
        order.put("member_id", MEMBER_ID_VALUE);
        order.put("amount", new BigDecimal("10000"));
        order.put("status", "APPROVED");
        when(tossChargeOrderMapper.findByOrderId(ORDER_ID)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("20000"))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(tossPaymentsClient, never()).confirmPayment(anyString(), anyString(), anyLong());
        verify(walletService, never()).getWallet(anyString());
        verify(tossPaymentClaim).release(ORDER_ID);
    }

    @Test
    void should_returnCurrentWallet_when_claimIsContendedButOrderAlreadyApproved() {
        init();
        doThrow(new BusinessException(HttpStatus.CONFLICT,
                "이미 처리 중이거나 최근에 처리된 결제 요청입니다."))
                .when(tossPaymentClaim).acquire(ORDER_ID);
        stubCompletedCharge(PAYMENT_KEY, new BigDecimal("10000"));
        WalletResponse expected = WalletResponse.builder()
                .walletId("1").memberId(MEMBER_ID).totalBalance(new BigDecimal("30000")).build();
        when(walletService.getWallet(MEMBER_ID)).thenReturn(expected);

        WalletResponse actual = service.charge(MEMBER_ID, request(new BigDecimal("10000")));

        assertSame(expected, actual);
        verify(tossPaymentsClient, never()).confirmPayment(anyString(), anyString(), anyLong());
        verify(walletService, never()).depositExternal(any());
        verify(tossPaymentClaim, never()).release(anyString());
    }

    @Test
    void should_recoverFromLedger_when_orderStatusUpdatePreviouslyFailed() {
        init();
        stubOrderFound();
        stubCompletedCharge(PAYMENT_KEY, new BigDecimal("10000"));
        WalletResponse expected = WalletResponse.builder()
                .walletId("1").memberId(MEMBER_ID).totalBalance(new BigDecimal("30000")).build();
        when(walletService.getWallet(MEMBER_ID)).thenReturn(expected);

        WalletResponse actual = service.charge(MEMBER_ID, request(new BigDecimal("10000")));

        assertSame(expected, actual);
        verify(tossPaymentsClient, never()).confirmPayment(anyString(), anyString(), anyLong());
        verify(walletService, never()).depositExternal(any());
        verify(tossChargeOrderMapper).updateStatus(ORDER_ID, "APPROVED");
        verify(tossPaymentClaim).release(ORDER_ID);
    }

    @Test
    void should_rejectRetry_when_paymentKeyDiffersFromCompletedLedger() {
        init();
        Map<String, Object> order = new HashMap<>();
        order.put("order_id", ORDER_ID);
        order.put("member_id", MEMBER_ID_VALUE);
        order.put("amount", new BigDecimal("10000"));
        order.put("status", "APPROVED");
        when(tossChargeOrderMapper.findByOrderId(ORDER_ID)).thenReturn(order);
        stubCompletedCharge("different-payment-key", new BigDecimal("10000"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("10000"))));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(tossPaymentsClient, never()).confirmPayment(anyString(), anyString(), anyLong());
        verify(walletService, never()).getWallet(anyString());
        verify(tossPaymentClaim).release(ORDER_ID);
    }

    @Test
    void should_keepConflict_when_claimIsContendedAndPaymentKeyDiffersFromLedger() {
        init();
        doThrow(new BusinessException(HttpStatus.CONFLICT,
                "이미 처리 중이거나 최근에 처리된 결제 요청입니다."))
                .when(tossPaymentClaim).acquire(ORDER_ID);
        stubCompletedCharge("different-payment-key", new BigDecimal("10000"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.charge(MEMBER_ID, request(new BigDecimal("10000"))));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(tossPaymentsClient, never()).confirmPayment(anyString(), anyString(), anyLong());
        verify(walletService, never()).getWallet(anyString());
        verify(tossChargeOrderMapper, never()).updateStatus(anyString(), anyString());
    }

    @Test
    void should_updateOrderStatusToApproved_when_chargeSucceeds() {
        init();
        stubOrderFound();
        stubWalletFound();
        when(tossPaymentsClient.confirmPayment(anyString(), anyString(), anyLong()))
                .thenReturn(TossConfirmResult.success(10000L, null));
        when(walletService.depositExternal(any())).thenReturn(
                WalletResponse.builder().walletId("1").memberId(MEMBER_ID)
                        .totalBalance(new BigDecimal("10000")).build());

        service.charge(MEMBER_ID, request(new BigDecimal("10000")));

        verify(tossChargeOrderMapper).updateStatus(ORDER_ID, "APPROVED");
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private void stubOrderFound() {
        Map<String, Object> order = new HashMap<>();
        order.put("order_id", ORDER_ID);
        order.put("member_id", MEMBER_ID_VALUE);
        order.put("amount", new BigDecimal("10000"));
        order.put("status", "PENDING");
        when(tossChargeOrderMapper.findByOrderId(ORDER_ID)).thenReturn(order);
    }

    private void stubWalletFound() {
        Map<String, Object> wallet = new HashMap<>();
        wallet.put("wallet_id", 1L);
        wallet.put("member_id", MEMBER_ID);
        wallet.put("balance", new BigDecimal("0"));
        when(walletMapper.findByMemberId(MEMBER_ID)).thenReturn(wallet);
    }

    private void stubCompletedCharge(String paymentKey, BigDecimal amount) {
        Map<String, Object> completedCharge = new HashMap<>();
        completedCharge.put("order_id", ORDER_ID);
        completedCharge.put("payment_key", paymentKey);
        completedCharge.put("price", amount);
        completedCharge.put("member_id", MEMBER_ID_VALUE);
        when(transactionMapper.findTossPaymentByOrderId(ORDER_ID)).thenReturn(completedCharge);
    }

    private TossChargeRequest request(BigDecimal amount) {
        TossChargeRequest request = new TossChargeRequest();
        ReflectionTestUtils.setField(request, "paymentKey", PAYMENT_KEY);
        ReflectionTestUtils.setField(request, "orderId", ORDER_ID);
        ReflectionTestUtils.setField(request, "amount", amount);
        return request;
    }
}
