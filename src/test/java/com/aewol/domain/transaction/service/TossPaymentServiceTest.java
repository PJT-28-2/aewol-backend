package com.aewol.domain.transaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.transaction.dto.PaymentRecordCommand;
import com.aewol.domain.transaction.dto.TossPaymentConfirmRequest;
import com.aewol.domain.transaction.dto.TransactionResponse;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.tosspayments.TossCancelResult;
import com.aewol.external.tosspayments.TossConfirmOutcome;
import com.aewol.external.tosspayments.TossConfirmResult;
import com.aewol.external.tosspayments.TossPaymentsClient;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * TossPaymentService의 5단계 오케스트레이션(클레임 -> 사전점검 -> confirm -> 원장기록 -> 보상)을
 * 검증한다. 특히 확정적 거부/불확정/이미승인/설정오류 네 가지 실패 분류가 각기 다른 클레임 처분과
 * 감사 로그 호출로 이어지는지, 그리고 승인 후 원장 실패 시의 보상 경로가 DuplicateKeyException
 * 경로와 명확히 구분되는지가 핵심 행위 검증이다.
 */
@ExtendWith(MockitoExtension.class)
class TossPaymentServiceTest {

    private static final String MEMBER_ID = "member-1";
    private static final String PAYMENT_KEY = "toss-payment-key-1";
    private static final String ORDER_ID = "order-1234567890";

    @Mock TossPaymentClaim tossPaymentClaim;
    @Mock TossPaymentsClient tossPaymentsClient;
    @Mock TossPaymentAuditLogger auditLogger;
    @Mock TransactionService transactionService;
    @Mock WalletMapper walletMapper;

    private TossPaymentService tossPaymentService;

    @BeforeEach
    void setUp() {
        tossPaymentService = new TossPaymentService(
                tossPaymentClaim, tossPaymentsClient, auditLogger, transactionService, walletMapper);
    }

    @Test
    @DisplayName("승인 성공 시 Toss가 확정한 금액으로 원장을 기록하고 paymentKey/orderId를 함께 저장한다")
    void should_recordPaymentWithTossConfirmedAmountAndKeys_when_confirmSucceeds() {
        // Toss 확정 금액(9500)이 요청 DTO 금액(10000)과 다르게 만들어야, command.amount가
        // 실제로 어느 쪽 소스에서 왔는지 이 테스트가 증명할 수 있다.
        TossPaymentConfirmRequest request = request(PAYMENT_KEY, ORDER_ID, BigDecimal.valueOf(10000));
        when(walletMapper.findByMemberId(MEMBER_ID)).thenReturn(walletWithBalance(20000));
        when(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, 10000L))
                .thenReturn(TossConfirmResult.success(9500L, Map.of("status", "DONE")));
        TransactionResponse response = TransactionResponse.builder().txnId("txn-1").build();
        when(transactionService.recordExternalPayment(any())).thenReturn(response);

        TransactionResponse result = tossPaymentService.confirm(MEMBER_ID, request);

        assertEquals(response, result);
        ArgumentCaptor<PaymentRecordCommand> captor = ArgumentCaptor.forClass(PaymentRecordCommand.class);
        verify(transactionService).recordExternalPayment(captor.capture());
        PaymentRecordCommand command = captor.getValue();
        assertEquals(PAYMENT_KEY, command.getPaymentKey());
        assertEquals(ORDER_ID, command.getOrderId());
        assertEquals(0, BigDecimal.valueOf(9500).compareTo(command.getAmount()));
    }

    @Test
    @DisplayName("사전 잔액 점검에서 잔액이 부족하면 Toss confirm을 호출하지 않고 클레임을 해제한다")
    void should_skipTossAndReleaseClaim_when_preflightBalanceInsufficient() {
        TossPaymentConfirmRequest request = request(PAYMENT_KEY, ORDER_ID, BigDecimal.valueOf(10000));
        when(walletMapper.findByMemberId(MEMBER_ID)).thenReturn(walletWithBalance(5000));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tossPaymentService.confirm(MEMBER_ID, request));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(tossPaymentsClient, never()).confirmPayment(anyString(), anyString(), anyLong());
        verify(tossPaymentClaim).release(MEMBER_ID, ORDER_ID);
    }

    @Test
    @DisplayName("Toss가 확정적으로 거절하면 402를 던지고 클레임을 해제하며 부작용/감사로그가 없다")
    void should_releaseClaimAndSkipAudit_when_definitiveRejection() {
        TossPaymentConfirmRequest request = request(PAYMENT_KEY, ORDER_ID, BigDecimal.valueOf(10000));
        when(walletMapper.findByMemberId(MEMBER_ID)).thenReturn(walletWithBalance(20000));
        when(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, 10000L))
                .thenReturn(TossConfirmResult.of(TossConfirmOutcome.DEFINITIVE_REJECTION, "REJECT_CARD_COMPANY",
                        "카드사에서 거절했어요", null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tossPaymentService.confirm(MEMBER_ID, request));

        assertEquals(HttpStatus.PAYMENT_REQUIRED, ex.getStatus());
        verify(tossPaymentClaim).release(MEMBER_ID, ORDER_ID);
        verify(transactionService, never()).recordExternalPayment(any());
        verifyNoInteractions(auditLogger);
    }

    @Test
    @DisplayName("Toss 불확정 실패(502)는 클레임을 유지하고 confirmIndeterminate를 기록한다")
    void should_keepClaimAndLogIndeterminate_when_confirmIndeterminate() {
        TossPaymentConfirmRequest request = request(PAYMENT_KEY, ORDER_ID, BigDecimal.valueOf(10000));
        when(walletMapper.findByMemberId(MEMBER_ID)).thenReturn(walletWithBalance(20000));
        when(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, 10000L))
                .thenReturn(TossConfirmResult.of(TossConfirmOutcome.INDETERMINATE, "PROVIDER_ERROR",
                        "일시적인 오류가 발생했어요", null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tossPaymentService.confirm(MEMBER_ID, request));

        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        verify(tossPaymentClaim, never()).release(anyString(), anyString());
        verify(auditLogger).confirmIndeterminate(eq(PAYMENT_KEY), eq(ORDER_ID), eq(MEMBER_ID), anyString());
    }

    @Test
    @DisplayName("Toss가 이미 승인됨으로 응답하면 409를 던지고 클레임을 유지하며 alreadyApproved를 기록한다")
    void should_keepClaimAndLogAlreadyApproved_when_alreadyApproved() {
        TossPaymentConfirmRequest request = request(PAYMENT_KEY, ORDER_ID, BigDecimal.valueOf(10000));
        when(walletMapper.findByMemberId(MEMBER_ID)).thenReturn(walletWithBalance(20000));
        when(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, 10000L))
                .thenReturn(TossConfirmResult.of(TossConfirmOutcome.ALREADY_APPROVED, "ALREADY_PROCESSED_PAYMENT",
                        "이미 처리된 결제예요", null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tossPaymentService.confirm(MEMBER_ID, request));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(tossPaymentClaim, never()).release(anyString(), anyString());
        verify(auditLogger).alreadyApproved(eq(PAYMENT_KEY), eq(ORDER_ID), eq(MEMBER_ID), anyString());
    }

    @Test
    @DisplayName("Toss 승인 후 원장 기록이 실패하면(TOCTOU) 결제를 취소하고 compensated를 기록한다")
    void should_cancelPaymentAndLogCompensated_when_ledgerWriteFailsAfterApproval() {
        TossPaymentConfirmRequest request = request(PAYMENT_KEY, ORDER_ID, BigDecimal.valueOf(10000));
        when(walletMapper.findByMemberId(MEMBER_ID)).thenReturn(walletWithBalance(20000));
        when(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, 10000L))
                .thenReturn(TossConfirmResult.success(10000L, Map.of("status", "DONE")));
        when(transactionService.recordExternalPayment(any()))
                .thenThrow(new BusinessException("잔액이 부족합니다."));
        when(tossPaymentsClient.cancelPayment(eq(PAYMENT_KEY), anyString()))
                .thenReturn(TossCancelResult.success());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tossPaymentService.confirm(MEMBER_ID, request));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());

        // 취소 사유에는 예외 메시지를 싣지 않는다. catch(Exception)이 의도적으로 넓어서
        // 예상 못 한 RuntimeException(드라이버 예외 등)의 메시지에 SQL 조각이나 내부
        // 식별자가 담길 수 있고, 그것이 외부 API 필드로 나가면 안 되기 때문이다.
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(tossPaymentsClient).cancelPayment(eq(PAYMENT_KEY), reasonCaptor.capture());
        assertFalse(reasonCaptor.getValue().contains("잔액이 부족합니다."),
                "취소 사유에 원인 예외 메시지가 포함되면 안 된다");

        // 원인 메시지는 외부에 노출되지 않는 감사 로그에만 남는다.
        verify(auditLogger).compensated(eq(PAYMENT_KEY), eq(ORDER_ID), eq(MEMBER_ID),
                contains("잔액이 부족합니다."));
    }

    @Test
    @DisplayName("승인 후 원장 실패 + 보상(cancel)마저 실패하면 compensationFailed를 기록한다")
    void should_logCompensationFailed_when_cancelPaymentAlsoFails() {
        TossPaymentConfirmRequest request = request(PAYMENT_KEY, ORDER_ID, BigDecimal.valueOf(10000));
        when(walletMapper.findByMemberId(MEMBER_ID)).thenReturn(walletWithBalance(20000));
        when(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, 10000L))
                .thenReturn(TossConfirmResult.success(10000L, Map.of("status", "DONE")));
        when(transactionService.recordExternalPayment(any()))
                .thenThrow(new BusinessException("잔액이 부족합니다."));
        when(tossPaymentsClient.cancelPayment(eq(PAYMENT_KEY), anyString()))
                .thenReturn(TossCancelResult.failure("FAILED_CANCEL", "취소 실패"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tossPaymentService.confirm(MEMBER_ID, request));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        verify(auditLogger).compensationFailed(eq(PAYMENT_KEY), eq(ORDER_ID), eq(MEMBER_ID), anyString());
        verify(auditLogger, never()).compensated(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("클레임 확보가 경합으로 실패하면 409가 전파되고 Toss confirm은 호출되지 않는다")
    void should_propagateConflictAndSkipToss_when_claimAcquireContended() {
        TossPaymentConfirmRequest request = request(PAYMENT_KEY, ORDER_ID, BigDecimal.valueOf(10000));
        doThrow(new BusinessException(HttpStatus.CONFLICT,
                "이미 처리 중이거나 최근에 처리된 결제 요청입니다. 새 주문번호로 다시 시도해 주세요."))
                .when(tossPaymentClaim).acquire(MEMBER_ID, ORDER_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tossPaymentService.confirm(MEMBER_ID, request));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(tossPaymentsClient, never()).confirmPayment(anyString(), anyString(), anyLong());
        verifyNoInteractions(walletMapper);
    }

    @Test
    @DisplayName("원장 insert가 DuplicateKeyException으로 실패하면 409를 반환하고 cancel 보상은 호출하지 않는다")
    void should_return409WithoutCancelCompensation_when_ledgerInsertThrowsDuplicateKeyException() {
        TossPaymentConfirmRequest request = request(PAYMENT_KEY, ORDER_ID, BigDecimal.valueOf(10000));
        when(walletMapper.findByMemberId(MEMBER_ID)).thenReturn(walletWithBalance(20000));
        when(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, 10000L))
                .thenReturn(TossConfirmResult.success(10000L, Map.of("status", "DONE")));
        when(transactionService.recordExternalPayment(any()))
                .thenThrow(new DuplicateKeyException("duplicate payment_key"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tossPaymentService.confirm(MEMBER_ID, request));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(tossPaymentsClient, never()).cancelPayment(anyString(), anyString());
    }

    @Test
    @DisplayName("설정 오류(401/403)는 500으로 응답하고 클레임을 해제하며 402(카드거절)로 오분류하지 않는다")
    void should_releaseClaimAndReturn500NotPaymentRequired_when_configError() {
        TossPaymentConfirmRequest request = request(PAYMENT_KEY, ORDER_ID, BigDecimal.valueOf(10000));
        when(walletMapper.findByMemberId(MEMBER_ID)).thenReturn(walletWithBalance(20000));
        when(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, 10000L))
                .thenReturn(TossConfirmResult.of(TossConfirmOutcome.CONFIG_ERROR, "INVALID_API_KEY",
                        "잘못된 시크릿 키예요", null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tossPaymentService.confirm(MEMBER_ID, request));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        assertNotEquals(HttpStatus.PAYMENT_REQUIRED, ex.getStatus());
        verify(tossPaymentClaim).release(MEMBER_ID, ORDER_ID);
    }

    private TossPaymentConfirmRequest request(String paymentKey, String orderId, BigDecimal amount) {
        TossPaymentConfirmRequest request = new TossPaymentConfirmRequest();
        ReflectionTestUtils.setField(request, "paymentKey", paymentKey);
        ReflectionTestUtils.setField(request, "orderId", orderId);
        ReflectionTestUtils.setField(request, "amount", amount);
        ReflectionTestUtils.setField(request, "merchantName", "애월동물병원");
        ReflectionTestUtils.setField(request, "petId", "1");
        ReflectionTestUtils.setField(request, "memo", "정기검진");
        return request;
    }

    private Map<String, Object> walletWithBalance(long balance) {
        Map<String, Object> wallet = new HashMap<>();
        wallet.put("wallet_id", 1L);
        wallet.put("balance", BigDecimal.valueOf(balance));
        return wallet;
    }
}
