package com.aewol.domain.account.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.aewol.common.exception.BusinessException;
import com.aewol.domain.account.dto.AccountPrimaryRequest;
import com.aewol.domain.account.dto.DepositConfirmRequest;
import com.aewol.domain.account.dto.DepositConfirmResponse;
import com.aewol.domain.account.mapper.AccountMapper;
import com.aewol.domain.account.mapper.AccountVerificationMapper;
import com.aewol.external.codef.CodefClient;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
@ExtendWith(MockitoExtension.class)
class AccountServiceImplTest {
    @Mock AccountMapper accountMapper;
    @Mock AccountVerificationMapper accountVerificationMapper;
    @Mock CodefClient codefClient;
    @Mock Environment environment;
    @InjectMocks AccountServiceImpl service;
    private static final String MEMBER_ID = "9001";
private static final String ACCOUNT_ID = "1";
    private static final String TRANSACTION_ID = "TX20260807abc";
    private static final String CORRECT_CODE = "포근애월";
    @Test
    @DisplayName("본인 소유의 활성 계좌를 대표 계좌로 설정한다")
    void should_setPrimary_when_ownerAndActiveAccount() {
        when(accountMapper.findByAccountId(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, MEMBER_ID, "ACTIVE", false))
                .thenReturn(accountRow(ACCOUNT_ID, MEMBER_ID, "ACTIVE", true));
        when(accountMapper.setPrimary(ACCOUNT_ID)).thenReturn(1);
        var result = service.setPrimaryAccount(MEMBER_ID, ACCOUNT_ID, request(true));
        assertTrue(result.getIsPrimary());
        verify(accountMapper).clearPrimaryByMemberId(MEMBER_ID);
        verify(accountMapper).setPrimary(ACCOUNT_ID);
    }
    @Test
    @DisplayName("findByAccountId 확인 이후 계좌가 동시에 연동 해제되면(setPrimary 영향 행 0) 예외를 던진다")
    void should_throwConflict_when_accountDisconnectedConcurrentlyBeforeSetPrimary() {
        when(accountMapper.findByAccountId(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, MEMBER_ID, "ACTIVE", false));
        when(accountMapper.setPrimary(ACCOUNT_ID)).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setPrimaryAccount(MEMBER_ID, ACCOUNT_ID, request(true)));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatus());
        verify(accountMapper).clearPrimaryByMemberId(MEMBER_ID);
    }
    @Test
    @DisplayName("isPrimary가 true가 아니면 예외를 던진다")
    void should_throwBadRequest_when_isPrimaryNotTrue() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setPrimaryAccount(MEMBER_ID, ACCOUNT_ID, request(false)));
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(accountMapper, never()).clearPrimaryByMemberId(any());
    }
    @Test
    @DisplayName("계좌를 찾을 수 없으면 404 예외를 던진다")
    void should_throwNotFound_when_accountDoesNotExist() {
        when(accountMapper.findByAccountId(ACCOUNT_ID)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setPrimaryAccount(MEMBER_ID, ACCOUNT_ID, request(true)));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatus());
    }
    @Test
    @DisplayName("다른 회원 소유의 계좌면 404 예외를 던진다")
    void should_throwNotFound_when_accountBelongsToAnotherMember() {
        when(accountMapper.findByAccountId(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, "9002", "ACTIVE", false));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setPrimaryAccount(MEMBER_ID, ACCOUNT_ID, request(true)));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatus());
    }
    @Test
    @DisplayName("연동 해제된(INACTIVE) 계좌는 대표 계좌로 설정할 수 없다")
    void should_throwConflict_when_accountInactive() {
        when(accountMapper.findByAccountId(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, MEMBER_ID, "INACTIVE", false));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.setPrimaryAccount(MEMBER_ID, ACCOUNT_ID, request(true)));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatus());
        verify(accountMapper, never()).clearPrimaryByMemberId(any());
    }
    @Test
    @DisplayName("대표 계좌를 해제하면 남은 ACTIVE 계좌 중 하나를 새 대표로 자동 승격한다")
    void should_promoteAnotherAccount_when_disconnectingPrimaryWithRemainingActiveAccounts() {
        String otherAccountId = "2";
        when(accountMapper.findByAccountId(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, MEMBER_ID, "ACTIVE", true));
        when(accountMapper.findByMemberId(MEMBER_ID))
                .thenReturn(List.of(accountRow(otherAccountId, MEMBER_ID, "ACTIVE", false)));
        service.disconnectAccount(ACCOUNT_ID);
        verify(accountMapper).updateStatus(ACCOUNT_ID, "INACTIVE");
        verify(accountMapper).setPrimary(otherAccountId);
    }
    @Test
    @DisplayName("대표 계좌가 아닌 계좌를 해제하면 대표 승격 로직을 타지 않는다")
    void should_notPromote_when_disconnectingNonPrimaryAccount() {
        when(accountMapper.findByAccountId(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, MEMBER_ID, "ACTIVE", false));
        service.disconnectAccount(ACCOUNT_ID);
        verify(accountMapper).updateStatus(ACCOUNT_ID, "INACTIVE");
        verify(accountMapper, never()).findByMemberId(any());
        verify(accountMapper, never()).setPrimary(any());
    }
    @Test
    @DisplayName("대표 계좌를 해제했는데 남은 ACTIVE 계좌가 없으면 대표 계좌 0개로 둔다")
    void should_leaveNoPrimary_when_disconnectingPrimaryWithNoRemainingAccounts() {
        when(accountMapper.findByAccountId(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, MEMBER_ID, "ACTIVE", true));
        when(accountMapper.findByMemberId(MEMBER_ID)).thenReturn(List.of());
        service.disconnectAccount(ACCOUNT_ID);
        verify(accountMapper, never()).setPrimary(any());
    }
    @Test
    @DisplayName("존재하지 않는 계좌를 해제하려 하면 404 예외를 던진다")
    void should_throwNotFound_when_disconnectingNonexistentAccount() {
        when(accountMapper.findByAccountId(ACCOUNT_ID)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.disconnectAccount(ACCOUNT_ID));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatus());
        verify(accountMapper, never()).updateStatus(any(), any());
    }
    @Test
    @DisplayName("입금자명이 일치하고 시도 횟수가 한도 미만이면 인증에 성공한다")
    void should_verify_when_codeCorrectAndUnderLimit() {
        when(accountVerificationMapper.findByIdForUpdate(TRANSACTION_ID))
                .thenReturn(pendingVerification(0));
        DepositConfirmResponse result = service.confirmDepositVerification(MEMBER_ID, confirmRequest(CORRECT_CODE));
        assertTrue(result.isVerified());
        assertNull(result.getReason());
        verify(accountVerificationMapper).updateStatus(TRANSACTION_ID, "VERIFIED");
        verify(accountVerificationMapper, never()).incrementAttemptCount(any());
    }
    @Test
    @DisplayName("confirm은 잠금 없는 findById가 아니라 FOR UPDATE로 행을 잠그는 findByIdForUpdate를 사용한다")
    void should_useLockingRead_notPlainFindById() {
        when(accountVerificationMapper.findByIdForUpdate(TRANSACTION_ID))
                .thenReturn(pendingVerification(0));
        service.confirmDepositVerification(MEMBER_ID, confirmRequest(CORRECT_CODE));
        verify(accountVerificationMapper).findByIdForUpdate(TRANSACTION_ID);
        verify(accountVerificationMapper, never()).findById(any());
    }
    @Test
    @DisplayName("입금자명이 틀리면 MISMATCH를 반환하고 시도 횟수를 올린다")
    void should_returnMismatch_and_incrementAttempt_when_codeWrong() {
        when(accountVerificationMapper.findByIdForUpdate(TRANSACTION_ID))
                .thenReturn(pendingVerification(0));
        DepositConfirmResponse result = service.confirmDepositVerification(MEMBER_ID, confirmRequest("틀린값"));
        assertFalse(result.isVerified());
        assertEquals("MISMATCH", result.getReason());
        verify(accountVerificationMapper).incrementAttemptCount(TRANSACTION_ID);
        verify(accountVerificationMapper, never()).updateStatus(any(), any());
    }
    @Test
    @DisplayName("시도 횟수가 한도(5회)에 도달하면 정답을 넣어도 통과시키지 않는다")
    void should_returnTooManyAttempts_when_attemptCountAtLimit() {
        when(accountVerificationMapper.findByIdForUpdate(TRANSACTION_ID))
                .thenReturn(pendingVerification(5));
        DepositConfirmResponse result = service.confirmDepositVerification(MEMBER_ID, confirmRequest(CORRECT_CODE));
        assertFalse(result.isVerified());
        assertEquals("TOO_MANY_ATTEMPTS", result.getReason());
        verify(accountVerificationMapper, never()).updateStatus(any(), any());
        verify(accountVerificationMapper, never()).incrementAttemptCount(any());
    }
    private AccountPrimaryRequest request(boolean isPrimary) {
        return new AccountPrimaryRequest(isPrimary);
    }
    private DepositConfirmRequest confirmRequest(String verificationCode) {
        return new DepositConfirmRequest(TRANSACTION_ID, verificationCode);
    }
    private Map<String, Object> accountRow(String accountId, String memberId, String status, boolean isPrimary) {
        Map<String, Object> row = new HashMap<>();
        row.put("account_id", accountId);
        row.put("member_id", memberId);
        row.put("bank_code", "004");
        row.put("bank_name", "KB국민은행");
        row.put("account_number", "1234567890");
        row.put("status", status);
        row.put("is_primary", isPrimary);
        return row;
    }
    private Map<String, Object> pendingVerification(int attemptCount) {
        Map<String, Object> row = new HashMap<>();
        row.put("transaction_id", TRANSACTION_ID);
        row.put("member_id", MEMBER_ID);
        row.put("bank_code", "004");
        row.put("account_number", "1234567890");
        row.put("verification_code", CORRECT_CODE);
        row.put("status", "PENDING");
        row.put("requested_at", LocalDateTime.now());
        row.put("attempt_count", attemptCount);
        return row;
    }
}


이거 맞아?