package com.aewol.domain.wallet.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.account.mapper.AccountMapper;
import com.aewol.domain.member.service.SimplePasswordVerificationService;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.dto.WalletWithdrawRequest;
import com.aewol.domain.wallet.dto.WalletWithdrawResponse;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.bank.BankWithdrawalGateway;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletWithdrawalServiceTest {

    @Mock AccountMapper accountMapper;
    @Mock WalletMapper walletMapper;
    @Mock TransactionMapper transactionMapper;
    @Mock SimplePasswordVerificationService simplePasswordVerificationService;
    @Mock BankWithdrawalGateway bankWithdrawalGateway;
    private WalletWithdrawalService service;

    @BeforeEach
    void setUp() {
        service = new WalletWithdrawalService(accountMapper, walletMapper, transactionMapper,
                simplePasswordVerificationService, bankWithdrawalGateway);
    }

    @Test
    void should_withdrawAndRecordTransaction_when_requestIsValid() {
        WalletWithdrawRequest request = request("12", "50000", "내 계좌로 출금", "482913");
        when(accountMapper.findByAccountIdForUpdate("12")).thenReturn(activeAccount("member-1"));
        when(simplePasswordVerificationService.verify("member-1", "482913")).thenReturn(true);
        when(walletMapper.findByMemberId("member-1"))
                .thenReturn(wallet("100000"), wallet("50000"));
        when(walletMapper.deductBalance("9001", new BigDecimal("50000"))).thenReturn(1);
        doAnswer(invocation -> {
            Map<String, Object> transaction = invocation.getArgument(0);
            transaction.put("txnId", 2003L);
            return null;
        }).when(transactionMapper).insert(any());

        WalletWithdrawResponse response = service.withdraw("member-1", request);

        assertEquals("2003", response.getTransactionId());
        assertEquals(new BigDecimal("50000"), response.getWalletBalance());
        assertEquals("12", response.getAccountId());
        assertEquals("KB국민은행", response.getBankName());
        assertEquals("********4444", response.getAccountNumberMasked());
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
                () -> service.withdraw("member-1", request));

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
                () -> service.withdraw("member-1", request));

        assertEquals(409, exception.getStatus().value());
        verifyNoInteractions(simplePasswordVerificationService, walletMapper,
                transactionMapper, bankWithdrawalGateway);
    }

    @Test
    void should_notDeductBalance_when_passwordDoesNotMatch() {
        WalletWithdrawRequest request = request("12", "50000", null, "000000");
        when(accountMapper.findByAccountIdForUpdate("12")).thenReturn(activeAccount("member-1"));
        when(simplePasswordVerificationService.verify("member-1", "000000")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", request));

        assertEquals(400, exception.getStatus().value());
        verifyNoInteractions(walletMapper, transactionMapper, bankWithdrawalGateway);
    }

    @Test
    void should_rejectWithdrawal_when_walletBalanceIsInsufficient() {
        WalletWithdrawRequest request = request("12", "50000", null, "482913");
        when(accountMapper.findByAccountIdForUpdate("12")).thenReturn(activeAccount("member-1"));
        when(simplePasswordVerificationService.verify("member-1", "482913")).thenReturn(true);
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet("10000"));
        when(walletMapper.deductBalance("9001", new BigDecimal("50000"))).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", request));

        assertEquals(409, exception.getStatus().value());
        verify(transactionMapper, never()).insert(any());
        verifyNoInteractions(bankWithdrawalGateway);
    }

    @Test
    void should_rejectWithdrawal_when_amountIsNotWholeWon() {
        WalletWithdrawRequest request = request("12", "1000.50", null, "482913");

        assertThrows(BusinessException.class, () -> service.withdraw("member-1", request));

        verifyNoInteractions(accountMapper, simplePasswordVerificationService, walletMapper,
                transactionMapper, bankWithdrawalGateway);
    }

    private WalletWithdrawRequest request(String accountId, String amount, String memo, String password) {
        return new WalletWithdrawRequest(accountId, new BigDecimal(amount), memo, password);
    }

    private Map<String, Object> activeAccount(String memberId) {
        return map("account_id", 12L, "member_id", memberId, "bank_code", "004",
                "bank_name", "KB국민은행", "account_number", "222233334444", "status", "ACTIVE");
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
