package com.aewol.domain.account.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.AccountNumberCrypto;
import com.aewol.domain.account.dto.AccountPrimaryRequest;
import com.aewol.domain.account.dto.AccountRegisterRequest;
import com.aewol.domain.account.dto.AccountResponse;
import com.aewol.domain.account.dto.DepositConfirmRequest;
import com.aewol.domain.account.dto.DepositConfirmResponse;
import com.aewol.domain.account.mapper.AccountMapper;
import com.aewol.domain.account.mapper.AccountVerificationMapper;
import com.aewol.external.codef.CodefClient;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
    @Mock AccountNumberCrypto accountNumberCrypto;
    @InjectMocks AccountServiceImpl service;
    private static final String MEMBER_ID = "9001";
private static final String ACCOUNT_ID = "1";
    private static final String TRANSACTION_ID = "TX20260807abc";
    private static final String CORRECT_CODE = "포근애월";

    // accountNumberCrypto는 실제 암복호화 로직 없이(unit test 범위 밖) "암호문 == 평문"으로
    // 취급하는 identity 스텁을 기본으로 깔아둔다 — toAccountResponse/registerAccount가
    // decrypt/hash를 호출할 때마다 값을 채워줘야 NPE 없이 각 테스트의 실제 관심사(대표 계좌
    // 전환, 동시성 처리 등)만 검증할 수 있다. lenient()라서 이 스텁을 안 쓰는 테스트에서도
    // UnnecessaryStubbingException 없이 통과한다.
    @BeforeEach
    void setUpCrypto() {
        lenient().when(accountNumberCrypto.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accountNumberCrypto.hash(anyString())).thenAnswer(inv -> "hash-" + inv.getArgument(0));
    }

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
        when(accountMapper.findByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, MEMBER_ID, "ACTIVE", true));
        when(accountMapper.findByMemberId(MEMBER_ID))
                .thenReturn(List.of(accountRow(otherAccountId, MEMBER_ID, "ACTIVE", false)));
        when(accountMapper.setPrimary(otherAccountId)).thenReturn(1);

        service.disconnectAccount(MEMBER_ID, ACCOUNT_ID);
        verify(accountMapper).updateStatus(ACCOUNT_ID, "INACTIVE");
        verify(accountMapper).findByAccountIdForUpdate(otherAccountId);
        verify(accountMapper).setPrimary(otherAccountId);
    }
    @Test
    @DisplayName("승격 후보 계좌를 잠근 뒤에도 setPrimary 영향 행이 0이면(동시에 연동 해제됨) 예외를 던지고 롤백한다")
    void should_throwConflict_when_candidateDisconnectedConcurrentlyBeforePromotion() {
        // findByMemberId로 후보를 고른 시점엔 ACTIVE였지만, findByAccountIdForUpdate로
        // 잠그기 직전 다른 트랜잭션이 그 계좌를 연동 해제해서 setPrimary의
        // WHERE status='ACTIVE' 조건에 안 걸리는 상황을 재현한다(CodeRabbit 지적, 2026-08-08).
        String otherAccountId = "2";
        when(accountMapper.findByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, MEMBER_ID, "ACTIVE", true));
        when(accountMapper.findByMemberId(MEMBER_ID))
                .thenReturn(List.of(accountRow(otherAccountId, MEMBER_ID, "ACTIVE", false)));
        when(accountMapper.setPrimary(otherAccountId)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.disconnectAccount(MEMBER_ID, ACCOUNT_ID));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("disconnectAccount는 잠금 없는 findByAccountId가 아니라 FOR UPDATE로 행을 잠그는 findByAccountIdForUpdate를 사용한다")
    void should_useLockingRead_notPlainFindByAccountId_whenDisconnecting() {
        // Mockito로는 실제 동시성 재현이 불가능해서, 서비스가 잠금 없는 findByAccountId를
        // 절대 호출하지 않는다는 걸로 회귀를 방지한다(기존 confirm의
        // should_useLockingRead_notPlainFindById와 같은 패턴).
        when(accountMapper.findByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, MEMBER_ID, "ACTIVE", false));

        service.disconnectAccount(MEMBER_ID, ACCOUNT_ID);

        verify(accountMapper).findByAccountIdForUpdate(ACCOUNT_ID);
        verify(accountMapper, never()).findByAccountId(any());
    }

    @Test
    @DisplayName("대표 계좌가 아닌 계좌를 해제하면 대표 승격 로직을 타지 않는다")
    void should_notPromote_when_disconnectingNonPrimaryAccount() {
        when(accountMapper.findByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, MEMBER_ID, "ACTIVE", false));
        service.disconnectAccount(MEMBER_ID, ACCOUNT_ID);
        verify(accountMapper).updateStatus(ACCOUNT_ID, "INACTIVE");
        verify(accountMapper, never()).findByMemberId(any());
        verify(accountMapper, never()).setPrimary(any());
    }
    @Test
    @DisplayName("대표 계좌를 해제했는데 남은 ACTIVE 계좌가 없으면 대표 계좌 0개로 둔다")
    void should_leaveNoPrimary_when_disconnectingPrimaryWithNoRemainingAccounts() {
        when(accountMapper.findByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, MEMBER_ID, "ACTIVE", true));
        when(accountMapper.findByMemberId(MEMBER_ID)).thenReturn(List.of());
        service.disconnectAccount(MEMBER_ID, ACCOUNT_ID);
        verify(accountMapper, never()).setPrimary(any());
    }
    @Test
    @DisplayName("존재하지 않는 계좌를 해제하려 하면 404 예외를 던진다")
    void should_throwNotFound_when_disconnectingNonexistentAccount() {
        when(accountMapper.findByAccountIdForUpdate(ACCOUNT_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.disconnectAccount(MEMBER_ID, ACCOUNT_ID));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatus());
        verify(accountMapper, never()).updateStatus(any(), any());
    }

    @Test
    @DisplayName("다른 회원 소유의 계좌는 연동 해제할 수 없다(소유자 검증 없으면 accountId만으로 남의 계좌를 해제할 수 있었음)")
    void should_throwNotFound_when_disconnectingAccountOwnedByAnotherMember() {
        // disconnectAccount가 memberId를 받지 않고 accountId만으로 처리하면, 이 API에
        // 접근 가능한 아무나 다른 회원의 계좌 ID로 계좌를 비활성화할 수 있었다(CodeRabbit
        // 지적, 2026-08-08). setPrimaryAccount와 동일하게 소유자가 다르면 404로 막는다.
        when(accountMapper.findByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(accountRow(ACCOUNT_ID, "9002", "ACTIVE", false));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.disconnectAccount(MEMBER_ID, ACCOUNT_ID));

        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatus());
        verify(accountMapper, never()).updateStatus(any(), any());
    }

    @Test
    @DisplayName("이미 대표 계좌가 있는 회원이 새 계좌를 isPrimary=true로 등록하면 기존 대표 계좌를 먼저 해제한다")
    void should_clearExistingPrimary_when_registeringNewAccountAsPrimary() {
        // setPrimaryAccount만 기존 대표를 내리고 registerAccount는 그대로 insert만 하면,
        // 이미 대표 계좌가 있는 회원이 새 계좌를 isPrimary=true로 등록할 때 대표 계좌가
        // 2개가 될 수 있었다(CodeRabbit 지적, 2026-08-08).
        Map<String, Object> verification = new HashMap<>();
        verification.put("member_id", MEMBER_ID);
        verification.put("status", "VERIFIED");
        verification.put("bank_code", "004");
        verification.put("account_number", "1234567890");
        when(accountVerificationMapper.findById(TRANSACTION_ID)).thenReturn(verification);
        when(accountVerificationMapper.markUsedIfVerified(TRANSACTION_ID)).thenReturn(1);
        doAnswer(invocation -> {
            Map<String, Object> arg = invocation.getArgument(0);
            arg.put("accountId", 99L);
            return null;
        }).when(accountMapper).insert(any());
        when(accountMapper.findByAccountId("99"))
                .thenReturn(accountRow("99", MEMBER_ID, "ACTIVE", true));

        AccountResponse result = service.registerAccount(MEMBER_ID, new AccountRegisterRequest(TRANSACTION_ID, true));

        assertTrue(result.getIsPrimary());
        var inOrder = inOrder(accountMapper);
        inOrder.verify(accountMapper).clearPrimaryByMemberId(MEMBER_ID);
        inOrder.verify(accountMapper).insert(any());
    }

    @Test
    @DisplayName("isPrimary를 안 넣고 계좌를 등록하면 기존 대표 계좌를 건드리지 않는다")
    void should_notClearPrimary_when_registeringWithoutPrimaryFlag() {
        Map<String, Object> verification = new HashMap<>();
        verification.put("member_id", MEMBER_ID);
        verification.put("status", "VERIFIED");
        verification.put("bank_code", "004");
        verification.put("account_number", "1234567890");
        when(accountVerificationMapper.findById(TRANSACTION_ID)).thenReturn(verification);
        when(accountVerificationMapper.markUsedIfVerified(TRANSACTION_ID)).thenReturn(1);
        doAnswer(invocation -> {
            Map<String, Object> arg = invocation.getArgument(0);
            arg.put("accountId", 100L);
            return null;
        }).when(accountMapper).insert(any());
        when(accountMapper.findByAccountId("100"))
                .thenReturn(accountRow("100", MEMBER_ID, "ACTIVE", false));

        service.registerAccount(MEMBER_ID, new AccountRegisterRequest(TRANSACTION_ID, null));

        verify(accountMapper, never()).clearPrimaryByMemberId(any());
    }

    @Test
    @DisplayName("계좌 등록 시 account_verification에 저장된 암호문을 그대로 옮기고, 해시는 복호화한 평문으로 만든다")
    void should_reuseCiphertext_and_hashPlainAccountNumber_when_registering() {
        // account_verification.account_number는 requestDepositVerification 시점에 이미
        // 암호화돼 있으므로, registerAccount는 그 값을 그대로 linked_account에 복사하기만
        // 하면 된다(다시 암호화하지 않음). 다만 중복 체크용 해시(HMAC)는 암호문끼리 비교할
        // 수 없어서(GCM 랜덤 IV) 항상 복호화한 평문 기준으로 만든다(2026-08-13).
        Map<String, Object> verification = new HashMap<>();
        verification.put("member_id", MEMBER_ID);
        verification.put("status", "VERIFIED");
        verification.put("bank_code", "004");
        verification.put("account_number", "ciphertext-abc");
        when(accountVerificationMapper.findById(TRANSACTION_ID)).thenReturn(verification);
        when(accountVerificationMapper.markUsedIfVerified(TRANSACTION_ID)).thenReturn(1);
        when(accountNumberCrypto.decrypt("ciphertext-abc")).thenReturn("1234567890");
        when(accountNumberCrypto.hash("1234567890")).thenReturn("hash-of-plain");
        doAnswer(invocation -> {
            Map<String, Object> arg = invocation.getArgument(0);
            arg.put("accountId", 101L);
            return null;
        }).when(accountMapper).insert(any());
        when(accountMapper.findByAccountId("101"))
                .thenReturn(accountRow("101", MEMBER_ID, "ACTIVE", false));

        service.registerAccount(MEMBER_ID, new AccountRegisterRequest(TRANSACTION_ID, null));

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(accountMapper).insert(captor.capture());
        assertEquals("ciphertext-abc", captor.getValue().get("accountNumber"));
        assertEquals("hash-of-plain", captor.getValue().get("accountNumberHash"));
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
        row.put("account_number_encrypted", "1234567890");
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
