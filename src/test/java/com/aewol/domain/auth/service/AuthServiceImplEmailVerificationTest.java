package com.aewol.domain.auth.service;

import com.aewol.common.cache.MemberAuthStateCache;
import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.JwtUtil;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.auth.dto.SignupEmailCodeRequest;
import com.aewol.domain.auth.dto.SignupEmailCodeResponse;
import com.aewol.domain.auth.dto.SignupEmailVerificationRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.notification.mapper.NotificationSettingMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.kakao.KakaoAuthClient;
import com.aewol.external.smtp.EmailSendException;
import com.aewol.external.smtp.EmailService;
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
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplEmailVerificationTest {

    private static final String EMAIL = "newuser@aewol.com";
    private static final String VERIFICATION_KEY = "signup:verify:" + EMAIL;
    private static final String COMPLETED_KEY = "signup:verify:completed:" + EMAIL;
    private static final String RATE_LIMIT_KEY = "signup:verify:request-count:" + EMAIL;

    @Mock MemberMapper memberMapper;
    @Mock WalletMapper walletMapper;
    @Mock NotificationSettingMapper notificationSettingMapper;
    @Mock JwtUtil jwtUtil;
    @Mock PasswordEncoder passwordEncoder;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock RedisRateLimiter redisRateLimiter;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock EmailService emailService;
    @Mock KakaoAuthClient kakaoAuthClient;
    @Mock AuthCredentialStore authCredentialStore;
    @Mock KakaoRegistrationStore kakaoRegistrationStore;
    @Mock TransactionOperations transactionOperations;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                memberMapper, walletMapper, notificationSettingMapper, jwtUtil, passwordEncoder,
                redisTemplate, redisRateLimiter, emailService, kakaoAuthClient, authCredentialStore,
                kakaoRegistrationStore, transactionOperations,
                MemberAuthStateCache.withoutCache(memberMapper));
    }

    @Test
    void sendsUuidAndSixDigitCodeUsingNewKeyForThreeHundredSeconds() {
        SignupEmailCodeRequest request = emailCodeRequest(EMAIL);
        when(memberMapper.existsActiveByEmail(EMAIL)).thenReturn(false);
        when(redisRateLimiter.incrementWithExpiry(RATE_LIMIT_KEY, 1800L)).thenReturn(1L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        SignupEmailCodeResponse response = authService.sendSignupVerificationCode(request);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq(VERIFICATION_KEY), valueCaptor.capture(), eq(300L), eq(TimeUnit.SECONDS));
        String[] valueParts = valueCaptor.getValue().split("\\|", -1);
        assertEquals(3, valueParts.length);
        assertTrue(valueParts[0].matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        assertTrue(valueParts[1].matches("\\d{6}"));
        assertEquals("0", valueParts[2]);
        verify(emailService).sendVerificationEmail(EMAIL, valueParts[1]);
        verify(redisRateLimiter).incrementWithExpiry(RATE_LIMIT_KEY, 1800L);
        verify(valueOperations, never()).set(
                eq("verify:" + EMAIL), anyString(), any(Long.class), any(TimeUnit.class));
        assertEquals(300L, response.getExpiresInSeconds());
    }

    @Test
    void rejectsEmailUsedByActiveMember() {
        SignupEmailCodeRequest request = emailCodeRequest("active@aewol.com");
        when(memberMapper.existsActiveByEmail(request.getEmail())).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.sendSignupVerificationCode(request));

        assertEquals(409, exception.getStatus().value());
        verify(redisRateLimiter, never()).incrementWithExpiry(
                anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(valueOperations, never()).set(
                anyString(), anyString(), any(Long.class), any(TimeUnit.class));
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    void firstFiveRequestsUseSameEmailRateLimitForThirtyMinutes() {
        SignupEmailCodeRequest request = emailCodeRequest(EMAIL);
        when(memberMapper.existsActiveByEmail(EMAIL)).thenReturn(false);
        when(redisRateLimiter.incrementWithExpiry(RATE_LIMIT_KEY, 1800L))
                .thenReturn(1L, 2L, 3L, 4L, 5L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        for (int requestCount = 1; requestCount <= 5; requestCount++) {
            SignupEmailCodeResponse response = authService.sendSignupVerificationCode(request);
            assertEquals(300L, response.getExpiresInSeconds());
        }

        verify(redisRateLimiter, times(5)).incrementWithExpiry(RATE_LIMIT_KEY, 1800L);
        verify(valueOperations, times(5)).set(
                eq(VERIFICATION_KEY), anyString(), eq(300L), eq(TimeUnit.SECONDS));
        verify(emailService, times(5)).sendVerificationEmail(eq(EMAIL), anyString());
    }

    @Test
    void sixthRequestReturnsTooManyRequestsWithoutChangingCurrentOtp() {
        SignupEmailCodeRequest request = emailCodeRequest(EMAIL);
        when(memberMapper.existsActiveByEmail(EMAIL)).thenReturn(false);
        when(redisRateLimiter.incrementWithExpiry(RATE_LIMIT_KEY, 1800L)).thenReturn(6L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.sendSignupVerificationCode(request));

        assertEquals(429, exception.getStatus().value());
        assertEquals("회원가입 인증번호 요청이 너무 많습니다. 30분 후 다시 시도해주세요.",
                exception.getMessage());
        verify(redisTemplate, never()).opsForValue();
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    void rateLimiterRedisFailureReturnsServiceUnavailable() {
        when(memberMapper.existsActiveByEmail(EMAIL)).thenReturn(false);
        when(redisRateLimiter.incrementWithExpiry(RATE_LIMIT_KEY, 1800L))
                .thenThrow(new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "요청 제한 확인에 실패했습니다."));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.sendSignupVerificationCode(emailCodeRequest(EMAIL)));

        assertEquals(503, exception.getStatus().value());
        verify(redisTemplate, never()).opsForValue();
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    void otpStoreRedisFailureReturnsServiceUnavailable() {
        when(memberMapper.existsActiveByEmail(EMAIL)).thenReturn(false);
        when(redisRateLimiter.incrementWithExpiry(RATE_LIMIT_KEY, 1800L)).thenReturn(1L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RuntimeException("redis unavailable")).when(valueOperations).set(
                eq(VERIFICATION_KEY), anyString(), eq(300L), eq(TimeUnit.SECONDS));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.sendSignupVerificationCode(emailCodeRequest(EMAIL)));

        assertEquals(503, exception.getStatus().value());
        assertEquals(null, exception.getErrorCode());
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    void resendingOverwritesOtpAndResetsAttemptsWhileKeepingRateLimitWindow() {
        SignupEmailCodeRequest request = emailCodeRequest("retry@aewol.com");
        when(memberMapper.existsActiveByEmail(request.getEmail())).thenReturn(false);
        when(redisRateLimiter.incrementWithExpiry(
                "signup:verify:request-count:retry@aewol.com", 1800L))
                .thenReturn(1L, 2L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(-1L, -1L, -1L);

        authService.sendSignupVerificationCode(request);
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThrows(BusinessException.class,
                    () -> authService.verifySignupEmailCode(
                            verificationRequest("retry@aewol.com", "000000")));
        }
        authService.sendSignupVerificationCode(request);

        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).set(
                eq("signup:verify:retry@aewol.com"), values.capture(), eq(300L), eq(TimeUnit.SECONDS));
        assertNotEquals(values.getAllValues().get(0), values.getAllValues().get(1));
        for (String value : values.getAllValues()) {
            String[] parts = value.split("\\|", -1);
            assertEquals(3, parts.length);
            assertEquals("0", parts[2]);
        }
        verify(redisRateLimiter, times(2)).incrementWithExpiry(
                "signup:verify:request-count:retry@aewol.com", 1800L);
        verify(emailService, times(2)).sendVerificationEmail(
                eq("retry@aewol.com"), anyString());
    }

    @Test
    void smtpFailureExecutesCompareAndDeleteWithCurrentFullValue() {
        SignupEmailCodeRequest request = emailCodeRequest("failure@aewol.com");
        when(memberMapper.existsActiveByEmail(request.getEmail())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        EmailSendException smtpException = new EmailSendException(
                "email send failed", new RuntimeException("provider detail"));
        doThrow(smtpException).when(emailService).sendVerificationEmail(anyString(), anyString());

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> authService.sendSignupVerificationCode(request));

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq("signup:verify:failure@aewol.com"), valueCaptor.capture(),
                eq(300L), eq(TimeUnit.SECONDS));
        verify(redisTemplate).execute(
                argThat(script -> script.getScriptAsString().contains("stored == ARGV[1]")
                        && script.getScriptAsString().contains("redis.call('DEL', KEYS[1])")),
                eq(List.of("signup:verify:failure@aewol.com")), eq(valueCaptor.getValue()));
        verify(redisTemplate, never()).delete(anyString());
        assertEquals(503, thrown.getStatus().value());
        assertEquals("인증 이메일을 발송할 수 없습니다. 잠시 후 다시 시도해주세요.",
                thrown.getMessage());
    }

    @Test
    void cleanupFailureIsSuppressedOnOriginalSmtpException() {
        SignupEmailCodeRequest request = emailCodeRequest("failure@aewol.com");
        when(memberMapper.existsActiveByEmail(request.getEmail())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        EmailSendException smtpException = new EmailSendException(
                "email send failed", new RuntimeException("provider detail"));
        RuntimeException redisException = new RuntimeException("redis failure");
        doThrow(smtpException).when(emailService).sendVerificationEmail(anyString(), anyString());
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(redisException);

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> authService.sendSignupVerificationCode(request));

        assertEquals(503, thrown.getStatus().value());
        assertEquals(1, smtpException.getSuppressed().length);
        assertSame(redisException, smtpException.getSuppressed()[0]);
    }

    @Test
    void verifyScriptSuccessReturnsNormallyWithOrderedKeysCodeAndTtl() {
        SignupEmailVerificationRequest request = verificationRequest(EMAIL, "123456");
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        authService.verifySignupEmailCode(request);

        verify(redisTemplate).execute(
                argThat(script -> {
                    String text = script.getScriptAsString();
                    return text.contains("redis.call('GET', KEYS[1])")
                            && text.contains("requestId, code, attempts = string.match")
                            && text.contains("code ~= ARGV[1]")
                            && text.contains("attempts >= tonumber(ARGV[2])")
                            && text.contains("'KEEPTTL'")
                            && text.contains("redis.call('DEL', KEYS[1])")
                            && text.contains("requestId .. '|' .. code, 'EX', ARGV[3]");
                }),
                eq(List.of(VERIFICATION_KEY, COMPLETED_KEY)),
                eq("123456"), eq("5"), eq("300"));
        verify(valueOperations, never()).get(anyString());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void verifyScriptMissingOrExpiredResultThrowsExistingException() {
        SignupEmailVerificationRequest request = verificationRequest(EMAIL, "123456");
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.verifySignupEmailCode(request));

        assertEquals(400, exception.getStatus().value());
        assertTrue(exception.getMessage().contains("만료"));
    }

    @Test
    void verifyScriptRedisFailureAndNullResultReturnServiceUnavailable() {
        SignupEmailVerificationRequest request = verificationRequest(EMAIL, "123456");
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("redis unavailable"))
                .thenReturn(null);

        for (int attempt = 0; attempt < 2; attempt++) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> authService.verifySignupEmailCode(request));
            assertEquals(503, exception.getStatus().value());
            assertEquals(null, exception.getErrorCode());
        }
    }

    @Test
    void verifyScriptMismatchResultThrowsExistingException() {
        SignupEmailVerificationRequest request = verificationRequest(EMAIL, "654321");
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(-1L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.verifySignupEmailCode(request));

        assertEquals(400, exception.getStatus().value());
        assertTrue(exception.getMessage().contains("일치하지"));
    }

    @Test
    void firstFourWrongAttemptsRetainOtpWithoutExtendingTtlOrCompletingVerification() {
        SignupEmailVerificationRequest request = verificationRequest(EMAIL, "654321");
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(-1L, -1L, -1L, -1L);

        for (int attempt = 1; attempt <= 4; attempt++) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> authService.verifySignupEmailCode(request));
            assertEquals("인증번호가 일치하지 않습니다.", exception.getMessage());
        }

        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate, times(4)).execute(
                script.capture(),
                eq(List.of(VERIFICATION_KEY, COMPLETED_KEY)),
                eq("654321"), eq("5"), eq("300"));
        String lua = script.getValue().getScriptAsString();
        assertTrue(lua.contains("attempts = tonumber(attempts) + 1"));
        assertTrue(lua.contains("attempts >= tonumber(ARGV[2])"));
        assertTrue(lua.contains("redis.call('DEL', KEYS[1])"));
        assertTrue(lua.contains("'KEEPTTL'"));
        int mismatchBranch = lua.indexOf("if code ~= ARGV[1]");
        int mismatchReturn = lua.indexOf("return -1", mismatchBranch);
        int completedWrite = lua.indexOf("redis.call('SET', KEYS[2]");
        assertTrue(mismatchBranch >= 0 && mismatchReturn > mismatchBranch);
        assertTrue(completedWrite > mismatchReturn);
    }

    @Test
    void fifthWrongAttemptInvalidatesOtpAndLaterCorrectCodeFails() {
        SignupEmailVerificationRequest wrong = verificationRequest(EMAIL, "654321");
        SignupEmailVerificationRequest correct = verificationRequest(EMAIL, "123456");
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(-1L, -1L, -1L, -1L, -1L, 0L);

        for (int attempt = 1; attempt <= 5; attempt++) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> authService.verifySignupEmailCode(wrong));
            assertEquals("인증번호가 일치하지 않습니다.", exception.getMessage());
        }
        BusinessException invalidated = assertThrows(BusinessException.class,
                () -> authService.verifySignupEmailCode(correct));

        assertTrue(invalidated.getMessage().contains("만료"));
        verify(redisTemplate, times(6)).execute(
                any(RedisScript.class),
                eq(List.of(VERIFICATION_KEY, COMPLETED_KEY)),
                anyString(), eq("5"), eq("300"));
    }

    private SignupEmailCodeRequest emailCodeRequest(String email) {
        SignupEmailCodeRequest request = new SignupEmailCodeRequest();
        ReflectionTestUtils.setField(request, "email", email);
        return request;
    }

    private SignupEmailVerificationRequest verificationRequest(String email, String code) {
        SignupEmailVerificationRequest request = new SignupEmailVerificationRequest();
        ReflectionTestUtils.setField(request, "email", email);
        ReflectionTestUtils.setField(request, "verificationCode", code);
        return request;
    }
}
