package com.aewol.domain.account.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aewol.domain.account.dto.DepositConfirmRequest;
import com.aewol.domain.account.dto.DepositConfirmResponse;
import com.aewol.domain.account.mapper.AccountMapper;
import com.aewol.domain.account.mapper.AccountVerificationMapper;
import com.aewol.external.codef.CodefClient;
import java.time.LocalDateTime;
import java.util.HashMap;
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
    private static final String TRANSACTION_ID = "TX20260807abc";
    private static final String CORRECT_CODE = "포근애월";

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
        // 동시에 들어온 confirm 요청들이 같은 attempt_count를 읽고 모두 통과하는 경합을
        // 막으려면 반드시 findByIdForUpdate(FOR UPDATE)로 행을 잠가야 한다(CodeRabbit
        // 지적, 2026-08-07). Mockito로는 실제 동시성 재현이 불가능해서, 대신 서비스가
        // 잠금 없는 findById를 절대 호출하지 않는다는 걸로 회귀를 방지한다.
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

    private DepositConfirmRequest confirmRequest(String verificationCode) {
        return new DepositConfirmRequest(TRANSACTION_ID, verificationCode);
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
