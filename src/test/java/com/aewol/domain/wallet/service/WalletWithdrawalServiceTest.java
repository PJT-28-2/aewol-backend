package com.aewol.domain.wallet.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.AccountNumberCrypto;
import com.aewol.domain.account.mapper.AccountMapper;
import com.aewol.domain.member.service.SimplePasswordVerificationService;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.dto.WalletWithdrawRequest;
import com.aewol.domain.wallet.dto.WalletWithdrawResponse;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.domain.wallet.mapper.WalletWithdrawalRequestMapper;
import com.aewol.external.bank.BankWithdrawalGateway;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class WalletWithdrawalServiceTest {

    @Mock AccountMapper accountMapper;
    @Mock WalletMapper walletMapper;
    @Mock TransactionMapper transactionMapper;
    @Mock WalletWithdrawalRequestMapper withdrawalRequestMapper;
    @Mock SimplePasswordVerificationService simplePasswordVerificationService;
    @Mock BankWithdrawalGateway bankWithdrawalGateway;
    @Mock AccountNumberCrypto accountNumberCrypto;
    private WalletWithdrawalService service;

    @BeforeEach
    void setUp() {
        service = new WalletWithdrawalService(accountMapper, walletMapper, transactionMapper, withdrawalRequestMapper,
                simplePasswordVerificationService, bankWithdrawalGateway, accountNumberCrypto);
        lenient().when(withdrawalRequestMapper.findByMemberIdAndKey(anyString(), anyString()))
                .thenReturn(null);
        // account_number는 이제 암호화된 값을 복호화해서 써야 한다(PR #162 리뷰 반영).
        // 테스트에서는 실제 암호화를 거치지 않으니, decrypt()가 입력을 그대로 돌려주게
        // 해서 저장된 테스트 값("222233334444")이 그대로 쓰이도록 한다.
        lenient().when(accountNumberCrypto.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void should_withdrawAndRecordTransaction_when_requestIsValid() {
        WalletWithdrawRequest request = request("12", "50000", "내 계좌로 출금", "482913");
        when(accountMapper.findByAccountIdForUpdate("12")).thenReturn(activeAccount("member-1"));
        when(simplePasswordVerificationService.verify("member-1", "482913")).thenReturn(true);
        when(walletMapper.findByMemberId("member-1"))
                .thenReturn(wallet("100000"), wallet("50000"));
        when(walletMapper.deductBalance("9001", new BigDecimal("50000"))).thenReturn(1);
        stubPendingInsert();
        doAnswer(invocation -> {
            Map<String, Object> transaction = invocation.getArgument(0);
            transaction.put("txnId", 2003L);
            return null;
        }).when(transactionMapper).insert(any());
        when(withdrawalRequestMapper.complete(any())).thenReturn(1);

        WalletWithdrawResponse response = service.withdraw("member-1", "withdraw-key-0001", request);

        assertEquals("2003", response.getTransactionId());
        assertEquals(new BigDecimal("50000"), response.getWalletBalance());
        assertEquals("12", response.getAccountId());
        assertEquals("KB국민은행", response.getBankName());
        assertEquals("********4444", response.getAccountNumberMasked());
        verify(accountNumberCrypto).decrypt("222233334444");
        verify(bankWithdrawalGateway).withdraw("004", "222233334444", new BigDecimal("50000"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(transactionMapper).insert(captor.capture());
        assertEquals("WITHDRAW", captor.getValue().get("txnType"));
        assertEquals(new BigDecimal("50000"), captor.getValue().get("price"));
        assertEquals("KB국민은행", captor.getValue().get("merchantName"));
    }

    @Test
    void should_rejectAccount_when_accountBelongsToAnotherMember() {
        WalletWithdrawRequest request = request("12", "50000", null, "482913");
        when(accountMapper.findByAccountIdForUpdate("12")).thenReturn(activeAccount("member-2"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", "withdraw-key-0001", request));

        assertEquals(404, exception.getStatus().value());
        verifyNoInteractions(simplePasswordVerificationService, walletMapper,
                transactionMapper, bankWithdrawalGateway);
    }

    @Test
    void should_rejectAccount_when_accountIsInactive() {
        WalletWithdrawRequest request = request("12", "50000", null, "482913");
        Map<String, Object> account = activeAccount("member-1");
        account.put("status", "INACTIVE");
        when(accountMapper.findByAccountIdForUpdate("12")).thenReturn(account);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", "withdraw-key-0001", request));

        assertEquals(409, exception.getStatus().value());
        verifyNoInteractions(simplePasswordVerificationService, walletMapper,
                transactionMapper, bankWithdrawalGateway);
    }

    @Test
    void should_notDeductBalance_when_passwordDoesNotMatch() {
        WalletWithdrawRequest request = request("12", "50000", null, "000000");
        when(accountMapper.findByAccountIdForUpdate("12")).thenReturn(activeAccount("member-1"));
        stubPendingInsert();
        when(simplePasswordVerificationService.verify("member-1", "000000")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", "withdraw-key-0001", request));

        assertEquals(400, exception.getStatus().value());
        verifyNoInteractions(walletMapper, transactionMapper, bankWithdrawalGateway);
    }

    @Test
    void should_rejectWithdrawal_when_walletBalanceIsInsufficient() {
        WalletWithdrawRequest request = request("12", "50000", null, "482913");
        when(accountMapper.findByAccountIdForUpdate("12")).thenReturn(activeAccount("member-1"));
        when(simplePasswordVerificationService.verify("member-1", "482913")).thenReturn(true);
        stubPendingInsert();
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet("10000"));
        when(walletMapper.deductBalance("9001", new BigDecimal("50000"))).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", "withdraw-key-0001", request));

        assertEquals(409, exception.getStatus().value());
        verify(transactionMapper, never()).insert(any());
        verifyNoInteractions(bankWithdrawalGateway);
    }

    @Test
    void should_rejectWithdrawal_when_amountIsNotWholeWon() {
        WalletWithdrawRequest request = request("12", "1000.50", null, "482913");

        assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", "withdraw-key-0001", request));

        verifyNoInteractions(accountMapper, withdrawalRequestMapper, simplePasswordVerificationService, walletMapper,
                transactionMapper, bankWithdrawalGateway);
    }

    @Test
    void should_returnFirstResultWithoutSecondDeduction_when_sameRequestIsRetried() {
        WalletWithdrawRequest request = request("12", "50000", "내 계좌로 출금", "482913");
        stubSuccessfulWithdrawal(request);

        WalletWithdrawResponse firstResponse = service.withdraw(
                "member-1", "withdraw-key-0001", request);

        ArgumentCaptor<Map<String, Object>> pendingCaptor = ArgumentCaptor.forClass(Map.class);
        verify(withdrawalRequestMapper).insertPending(pendingCaptor.capture());
        when(withdrawalRequestMapper.findByMemberIdAndKey("member-1", "withdraw-key-0001"))
                .thenReturn(completedRequest(String.valueOf(pendingCaptor.getValue().get("requestHash"))));

        WalletWithdrawResponse retriedResponse = service.withdraw(
                "member-1", "withdraw-key-0001", request);

        assertEquals(firstResponse.getTransactionId(), retriedResponse.getTransactionId());
        assertEquals(firstResponse.getWalletBalance(), retriedResponse.getWalletBalance());
        verify(walletMapper, times(1)).deductBalance("9001", new BigDecimal("50000"));
        verify(bankWithdrawalGateway, times(1))
                .withdraw("004", "222233334444", new BigDecimal("50000"));
        verify(transactionMapper, times(1)).insert(any());
    }

    @Test
    void should_rejectRequest_when_sameIdempotencyKeyIsUsedWithDifferentPayload() {
        WalletWithdrawRequest firstRequest = request("12", "50000", "내 계좌로 출금", "482913");
        stubSuccessfulWithdrawal(firstRequest);
        service.withdraw("member-1", "withdraw-key-0001", firstRequest);

        ArgumentCaptor<Map<String, Object>> pendingCaptor = ArgumentCaptor.forClass(Map.class);
        verify(withdrawalRequestMapper).insertPending(pendingCaptor.capture());
        when(withdrawalRequestMapper.findByMemberIdAndKey("member-1", "withdraw-key-0001"))
                .thenReturn(completedRequest(String.valueOf(pendingCaptor.getValue().get("requestHash"))));

        WalletWithdrawRequest changedRequest = request("12", "60000", "내 계좌로 출금", "482913");
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", "withdraw-key-0001", changedRequest));

        assertEquals(409, exception.getStatus().value());
        assertEquals("동일한 멱등키를 다른 출금 요청에 사용할 수 없습니다.", exception.getMessage());
        verify(walletMapper, times(1)).deductBalance("9001", new BigDecimal("50000"));
        verify(bankWithdrawalGateway, times(1))
                .withdraw("004", "222233334444", new BigDecimal("50000"));
        verify(transactionMapper, times(1)).insert(any());
    }

    @Test
    void should_rejectWithdrawal_when_idempotencyKeyFormatIsInvalid() {
        WalletWithdrawRequest request = request("12", "50000", null, "482913");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", "short", request));

        assertEquals(400, exception.getStatus().value());
        verifyNoInteractions(accountMapper, withdrawalRequestMapper, simplePasswordVerificationService,
                walletMapper, transactionMapper, bankWithdrawalGateway);
    }

    @Test
    void should_reuseCompletedResult_when_concurrentRequestWinsUniqueKeyInsert() {
        WalletWithdrawRequest request = request("12", "50000", "내 계좌로 출금", "482913");
        when(accountMapper.findByAccountIdForUpdate("12")).thenReturn(activeAccount("member-1"));
        AtomicReference<Map<String, Object>> concurrentRequest = new AtomicReference<>();
        when(withdrawalRequestMapper.findByMemberIdAndKey("member-1", "withdraw-key-0001"))
                .thenAnswer(invocation -> concurrentRequest.get());
        doAnswer(invocation -> {
            Map<String, Object> pendingRequest = invocation.getArgument(0);
            concurrentRequest.set(completedRequest(String.valueOf(pendingRequest.get("requestHash"))));
            throw new DuplicateKeyException("duplicate idempotency key");
        }).when(withdrawalRequestMapper).insertPending(any());

        WalletWithdrawResponse response = service.withdraw(
                "member-1", "withdraw-key-0001", request);

        assertEquals("2003", response.getTransactionId());
        assertEquals(new BigDecimal("50000"), response.getWalletBalance());
        verifyNoInteractions(simplePasswordVerificationService, walletMapper,
                transactionMapper, bankWithdrawalGateway);
    }

    private WalletWithdrawRequest request(String accountId, String amount, String memo, String password) {
        return new WalletWithdrawRequest(accountId, new BigDecimal(amount), memo, password);
    }

    private void stubPendingInsert() {
        doAnswer(invocation -> {
            Map<String, Object> pendingRequest = invocation.getArgument(0);
            pendingRequest.put("withdrawalRequestId", 100L);
            return null;
        }).when(withdrawalRequestMapper).insertPending(any());
    }

    private void stubSuccessfulWithdrawal(WalletWithdrawRequest request) {
        when(accountMapper.findByAccountIdForUpdate(request.getAccountId()))
                .thenReturn(activeAccount("member-1"));
        when(simplePasswordVerificationService.verify("member-1", request.getPassword())).thenReturn(true);
        when(walletMapper.findByMemberId("member-1"))
                .thenReturn(wallet("100000"), wallet("50000"));
        when(walletMapper.deductBalance("9001", request.getAmount())).thenReturn(1);
        stubPendingInsert();
        doAnswer(invocation -> {
            Map<String, Object> transaction = invocation.getArgument(0);
            transaction.put("txnId", 2003L);
            return null;
        }).when(transactionMapper).insert(any());
        when(withdrawalRequestMapper.complete(any())).thenReturn(1);
    }

    private Map<String, Object> completedRequest(String requestHash) {
        return map("request_hash", requestHash, "status", "COMPLETED",
                "account_id", 12L, "transaction_id", 2003L,
                "wallet_balance_after", new BigDecimal("50000"),
                "bank_name", "KB국민은행", "account_number_masked", "********4444",
                "withdrawn_at", LocalDateTime.of(2026, 8, 12, 15, 24));
    }

    private Map<String, Object> activeAccount(String memberId) {
        return map("account_id", 12L, "member_id", memberId, "bank_code", "004",
                "bank_name", "KB국민은행", "account_number_encrypted", "222233334444", "status", "ACTIVE");
    }

    private Map<String, Object> wallet(String balance) {
        return map("wallet_id", 9001L, "member_id", "member-1", "balance", new BigDecimal(balance));
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }
}
