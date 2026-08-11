package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.JwtUtil;
import com.aewol.domain.auth.dto.PasswordResetEmailRequest;
import com.aewol.domain.auth.dto.PasswordResetRequest;
import com.aewol.domain.auth.dto.PasswordResetVerifyRequest;
import com.aewol.domain.auth.dto.PasswordResetVerifyResponse;
import com.aewol.domain.auth.dto.SignupEmailCodeResponse;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.notification.mapper.NotificationSettingMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.kakao.KakaoAuthClient;
import com.aewol.external.smtp.EmailService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplPasswordResetTest {

    private static final String EMAIL = "local@aewol.com";

    @Mock MemberMapper memberMapper;
    @Mock WalletMapper walletMapper;
    @Mock NotificationSettingMapper notificationSettingMapper;
    @Mock JwtUtil jwtUtil;
    @Mock PasswordEncoder passwordEncoder;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock EmailService emailService;
    @Mock KakaoAuthClient kakaoAuthClient;
    @Mock AuthCredentialStore authCredentialStore;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(
                memberMapper, walletMapper, notificationSettingMapper, jwtUtil, passwordEncoder,
                redisTemplate, emailService, kakaoAuthClient, authCredentialStore);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void activeLocalResetRequestStoresOtpAndSendsMail() {
        when(memberMapper.findActiveByEmail(EMAIL)).thenReturn(member("LOCAL", true, "old-hash"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        SignupEmailCodeResponse response = service.sendPasswordResetVerificationCode(emailRequest(EMAIL));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq("password:reset:verify:" + EMAIL), value.capture(), eq(300L), eq(TimeUnit.SECONDS));
        String[] parts = value.getValue().split("\\|", -1);
        assertEquals(3, parts.length);
        assertTrue(parts[0].matches("[0-9a-f-]{36}"));
        assertTrue(parts[1].matches("\\d{6}"));
        assertEquals("0", parts[2]);
        verify(emailService).sendPasswordResetEmail(EMAIL, parts[1]);
        assertEquals(300L, response.getExpiresInSeconds());
    }

    @Test
    void resendOverwritesOtpSoPreviousStoredValueIsReplaced() {
        when(memberMapper.findActiveByEmail(EMAIL)).thenReturn(member("LOCAL", true, "old-hash"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.sendPasswordResetVerificationCode(emailRequest(EMAIL));
        service.sendPasswordResetVerificationCode(emailRequest(EMAIL));

        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).set(
                eq("password:reset:verify:" + EMAIL), values.capture(), eq(300L), eq(TimeUnit.SECONDS));
        assertNotEquals(values.getAllValues().get(0), values.getAllValues().get(1));
    }

    @Test
    void unknownKakaoAndInactiveRequestsReturnSameSuccessWithoutMail() {
        when(memberMapper.findActiveByEmail("unknown@aewol.com")).thenReturn(null);
        when(memberMapper.findActiveByEmail("kakao@aewol.com"))
                .thenReturn(member("KAKAO", true, null));
        when(memberMapper.findActiveByEmail("inactive@aewol.com")).thenReturn(null);

        assertEquals(300L, service.sendPasswordResetVerificationCode(
                emailRequest("unknown@aewol.com")).getExpiresInSeconds());
        assertEquals(300L, service.sendPasswordResetVerificationCode(
                emailRequest("kakao@aewol.com")).getExpiresInSeconds());
        assertEquals(300L, service.sendPasswordResetVerificationCode(
                emailRequest("inactive@aewol.com")).getExpiresInSeconds());

        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void successfulOtpVerificationIssuesOpaqueResetTokenAndConsumesOtp() {
        when(memberMapper.findActiveByEmail(EMAIL)).thenReturn(member("LOCAL", true, "old-hash"));
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        PasswordResetVerifyResponse response = service.verifyPasswordResetCode(
                verifyRequest(EMAIL, "123456"));

        assertTrue(response.getResetToken().matches("[0-9a-f-]{36}"));
        verify(redisTemplate).execute(
                argThat(script -> {
                    String lua = script.getScriptAsString();
                    return lua.contains("attempts >= tonumber(ARGV[2])")
                            && lua.contains("redis.call('DEL', KEYS[1])")
                            && lua.contains("'EX', ARGV[4], 'NX'");
                }),
                argThat(keys -> keys.get(0).equals("password:reset:verify:" + EMAIL)
                        && keys.get(1).equals("password:reset:token:" + response.getResetToken())),
                eq("123456"), eq("5"), eq("1"), eq("300"));
    }

    @Test
    void wrongAndMissingOtpUseExistingBadRequestMessages() {
        when(memberMapper.findActiveByEmail(EMAIL)).thenReturn(member("LOCAL", true, "old-hash"));
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(-1L, 0L);

        BusinessException mismatch = assertThrows(BusinessException.class,
                () -> service.verifyPasswordResetCode(verifyRequest(EMAIL, "111111")));
        BusinessException missing = assertThrows(BusinessException.class,
                () -> service.verifyPasswordResetCode(verifyRequest(EMAIL, "111111")));

        assertEquals("인증번호가 일치하지 않습니다.", mismatch.getMessage());
        assertEquals("인증번호가 만료되었거나 발급되지 않았습니다.", missing.getMessage());
        assertEquals(400, mismatch.getStatus().value());
        assertEquals(400, missing.getStatus().value());
    }

    @Test
    void fifthWrongOtpDeletesItAndLaterVerificationIsMissing() {
        when(memberMapper.findActiveByEmail(EMAIL)).thenReturn(member("LOCAL", true, "old-hash"));
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(-1L, -1L, -1L, -1L, -1L, 0L);

        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals("인증번호가 일치하지 않습니다.",
                    assertThrows(BusinessException.class,
                            () -> service.verifyPasswordResetCode(verifyRequest(EMAIL, "111111")))
                            .getMessage());
        }
        assertEquals("인증번호가 만료되었거나 발급되지 않았습니다.",
                assertThrows(BusinessException.class,
                        () -> service.verifyPasswordResetCode(verifyRequest(EMAIL, "123456")))
                        .getMessage());
    }

    @Test
    void successfulOtpCannotBeReused() {
        when(memberMapper.findActiveByEmail(EMAIL)).thenReturn(member("LOCAL", true, "old-hash"));
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L, 0L);

        service.verifyPasswordResetCode(verifyRequest(EMAIL, "123456"));
        BusinessException reused = assertThrows(BusinessException.class,
                () -> service.verifyPasswordResetCode(verifyRequest(EMAIL, "123456")));

        assertEquals("인증번호가 만료되었거나 발급되지 않았습니다.", reused.getMessage());
    }

    @Test
    void validTokenEncodesAndUpdatesPasswordThenDeletesRefreshAfterCommit() {
        stubClaim("1");
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", true, "old-hash"));
        when(passwordEncoder.matches("new-password", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
        when(memberMapper.updatePassword("1", "new-hash")).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        service.resetPassword(resetRequest("token", "new-password"));
        verify(memberMapper).updatePassword("1", "new-hash");
        verify(authCredentialStore, never()).deleteRefresh("1");

        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(authCredentialStore).deleteRefresh("1");
        verify(redisTemplate, atLeastOnce()).execute(
                argThat(script -> script.getScriptAsString().contains("redis.call('DEL', KEYS[1])")
                        && script.getScriptAsString().contains("ARGV[1]")),
                eq(List.of("password:reset:token:token")), anyString());
    }

    @Test
    void invalidExpiredAndUsedTokenFailClosed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resetPassword(resetRequest("invalid", "new-password")));

        assertEquals(400, exception.getStatus().value());
        assertEquals("유효하지 않거나 만료된 비밀번호 재설정 토큰입니다.", exception.getMessage());
        verify(memberMapper, never()).updatePassword(anyString(), anyString());
    }

    @Test
    void resetTokenIsConsumedOnlyOnce() {
        stubClaim("1");
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", true, "old-hash"));
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
        when(memberMapper.updatePassword("1", "new-hash")).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        service.resetPassword(resetRequest("token", "new-password"));
        TransactionSynchronizationUtils.triggerAfterCommit();
        TransactionSynchronizationManager.clearSynchronization();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> service.resetPassword(resetRequest("token", "another-password")));
        verify(memberMapper, times(1)).updatePassword(anyString(), anyString());
    }

    @Test
    void samePasswordIsRejectedUsingStoredBcryptHash() {
        stubClaim("1");
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", true, "old-hash"));
        when(passwordEncoder.matches("same-password", "old-hash")).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resetPassword(resetRequest("token", "same-password")));

        assertEquals("새 비밀번호는 현재 비밀번호와 달라야 합니다.", exception.getMessage());
        verify(passwordEncoder, never()).encode(anyString());
        verify(memberMapper, never()).updatePassword(anyString(), anyString());
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verifyRestoreUsesClaimedValueAndKeepsTtl();
    }

    @Test
    void finalResetRejectsKakaoAndInactiveMemberWithoutCreatingPassword() {
        stubClaim("2");
        when(memberMapper.findById("2")).thenReturn(member("KAKAO", true, null));
        when(memberMapper.findById("3")).thenReturn(member("LOCAL", false, "old-hash"));
        TransactionSynchronizationManager.initSynchronization();

        assertThrows(BusinessException.class,
                () -> service.resetPassword(resetRequest("kakao-token", "new-password")));
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        TransactionSynchronizationManager.clearSynchronization();
        stubClaim("3");
        TransactionSynchronizationManager.initSynchronization();
        assertThrows(BusinessException.class,
                () -> service.resetPassword(resetRequest("inactive-token", "new-password")));
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(passwordEncoder, never()).encode(anyString());
        verify(memberMapper, never()).updatePassword(anyString(), anyString());
    }

    @Test
    void refreshCleanupFailureDoesNotChangeCommittedPasswordOutcome() {
        stubClaim("1");
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", true, "old-hash"));
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
        when(memberMapper.updatePassword("1", "new-hash")).thenReturn(1);
        doThrow(new RuntimeException("redis unavailable"))
                .when(authCredentialStore).deleteRefresh("1");
        TransactionSynchronizationManager.initSynchronization();

        service.resetPassword(resetRequest("token", "new-password"));

        assertDoesNotThrow(TransactionSynchronizationUtils::triggerAfterCommit);
        verify(memberMapper).updatePassword("1", "new-hash");
        verify(authCredentialStore).deleteRefresh("1");
    }

    @Test
    void claimIsAtomicAndKeepsOriginalTtl() {
        stubClaim("1");
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", true, "old-hash"));
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
        when(memberMapper.updatePassword("1", "new-hash")).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        service.resetPassword(resetRequest("token", "new-password"));

        verify(redisTemplate, atLeastOnce()).execute(
                argThat(script -> {
                    String lua = script.getScriptAsString();
                    return lua.contains("string.sub(stored, 1, 8) == 'CLAIMED|'")
                            && lua.contains("'KEEPTTL'");
                }), eq(List.of("password:reset:token:token")), anyString());
    }

    @Test
    void rowCountAndDbFailureRestoreToken() {
        stubClaim("1");
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", true, "old-hash"));
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
        when(memberMapper.updatePassword("1", "new-hash"))
                .thenReturn(0)
                .thenThrow(new RuntimeException("db unavailable"));
        TransactionSynchronizationManager.initSynchronization();

        assertThrows(BusinessException.class,
                () -> service.resetPassword(resetRequest("token", "new-password")));
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        TransactionSynchronizationManager.clearSynchronization();
        stubClaim("1");
        TransactionSynchronizationManager.initSynchronization();
        assertThrows(RuntimeException.class,
                () -> service.resetPassword(resetRequest("token", "new-password")));
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyRestoreUsesClaimedValueAndKeepsTtl();
    }

    @Test
    void deleteFailureLeavesClaimedTokenAndDoesNotRestoreIt() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenAnswer(invocation -> {
                    RedisScript<?> script = invocation.getArgument(0);
                    if (script.getResultType() == String.class) {
                        return "1";
                    }
                    throw new RuntimeException("redis unavailable");
                });
        when(memberMapper.findById("1")).thenReturn(member("LOCAL", true, "old-hash"));
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
        when(memberMapper.updatePassword("1", "new-hash")).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        service.resetPassword(resetRequest("token", "new-password"));

        assertDoesNotThrow(TransactionSynchronizationUtils::triggerAfterCommit);
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        verify(redisTemplate, never()).execute(
                argThat(script -> script.getScriptAsString().contains("ARGV[2]")),
                anyList(), anyString(), anyString());
    }

    private void stubClaim(String memberId) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenAnswer(invocation -> {
                    RedisScript<?> script = invocation.getArgument(0);
                    return script.getResultType() == String.class ? memberId : 1L;
                });
    }

    private void verifyRestoreUsesClaimedValueAndKeepsTtl() {
        verify(redisTemplate, atLeastOnce()).execute(
                argThat(script -> script.getScriptAsString().contains("'KEEPTTL'")
                        && script.getScriptAsString().contains("ARGV[2]")),
                anyList(),
                argThat((Object value) -> value instanceof String
                        && ((String) value).startsWith("CLAIMED|")
                        && ((String) value).endsWith("|1")),
                eq("1"));
    }

    private PasswordResetEmailRequest emailRequest(String email) {
        PasswordResetEmailRequest request = new PasswordResetEmailRequest();
        ReflectionTestUtils.setField(request, "email", email);
        return request;
    }

    private PasswordResetVerifyRequest verifyRequest(String email, String code) {
        PasswordResetVerifyRequest request = new PasswordResetVerifyRequest();
        ReflectionTestUtils.setField(request, "email", email);
        ReflectionTestUtils.setField(request, "verificationCode", code);
        return request;
    }

    private PasswordResetRequest resetRequest(String token, String password) {
        PasswordResetRequest request = new PasswordResetRequest();
        ReflectionTestUtils.setField(request, "resetToken", token);
        ReflectionTestUtils.setField(request, "newPassword", password);
        return request;
    }

    private Map<String, Object> member(String provider, boolean active, String password) {
        Map<String, Object> member = new HashMap<>();
        member.put("member_id", provider.equals("KAKAO") ? 2L : 1L);
        member.put("provider", provider);
        member.put("is_active", active ? 1 : 0);
        member.put("password", password);
        return member;
    }
}
