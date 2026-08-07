package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.JwtUtil;
import com.aewol.domain.auth.dto.SignupEmailCodeRequest;
import com.aewol.domain.auth.dto.SignupEmailCodeResponse;
import com.aewol.domain.auth.dto.SignupEmailVerificationRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.notification.mapper.NotificationSettingMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.kakao.KakaoAuthClient;
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

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                memberMapper, walletMapper, notificationSettingMapper, jwtUtil, passwordEncoder,
                redisTemplate, emailService, kakaoAuthClient, authCredentialStore);
        lenient().when(authCredentialStore.storeRefreshIfEpochUnchanged(anyString(), any(), anyString()))
                .thenReturn(true);
    }

    @Test
    void sendsUuidAndSixDigitCodeUsingNewKeyForThreeHundredSeconds() {
        SignupEmailCodeRequest request = emailCodeRequest(EMAIL);
        when(memberMapper.existsActiveByEmail(EMAIL)).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        SignupEmailCodeResponse response = authService.sendSignupVerificationCode(request);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq(VERIFICATION_KEY), valueCaptor.capture(), eq(300L), eq(TimeUnit.SECONDS));
        String[] valueParts = valueCaptor.getValue().split("\\|", -1);
        assertEquals(2, valueParts.length);
        assertTrue(valueParts[0].matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        assertTrue(valueParts[1].matches("\\d{6}"));
        verify(emailService).sendVerificationEmail(EMAIL, valueParts[1]);
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
        verify(valueOperations, never()).set(
                anyString(), anyString(), any(Long.class), any(TimeUnit.class));
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    void resendingOverwritesNewKeyAndRefreshesTtl() {
        SignupEmailCodeRequest request = emailCodeRequest("retry@aewol.com");
        when(memberMapper.existsActiveByEmail(request.getEmail())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        authService.sendSignupVerificationCode(request);
        authService.sendSignupVerificationCode(request);

        verify(valueOperations, times(2)).set(
                eq("signup:verify:retry@aewol.com"), anyString(), eq(300L), eq(TimeUnit.SECONDS));
        verify(emailService, times(2)).sendVerificationEmail(
                eq("retry@aewol.com"), anyString());
    }

    @Test
    void smtpFailureExecutesCompareAndDeleteWithCurrentFullValue() {
        SignupEmailCodeRequest request = emailCodeRequest("failure@aewol.com");
        when(memberMapper.existsActiveByEmail(request.getEmail())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RuntimeException smtpException = new RuntimeException("smtp failure");
        doThrow(smtpException).when(emailService).sendVerificationEmail(anyString(), anyString());

        RuntimeException thrown = assertThrows(RuntimeException.class,
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
        assertSame(smtpException, thrown);
    }

    @Test
    void cleanupFailureIsSuppressedOnOriginalSmtpException() {
        SignupEmailCodeRequest request = emailCodeRequest("failure@aewol.com");
        when(memberMapper.existsActiveByEmail(request.getEmail())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RuntimeException smtpException = new RuntimeException("smtp failure");
        RuntimeException redisException = new RuntimeException("redis failure");
        doThrow(smtpException).when(emailService).sendVerificationEmail(anyString(), anyString());
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(redisException);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> authService.sendSignupVerificationCode(request));

        assertSame(smtpException, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(redisException, thrown.getSuppressed()[0]);
    }

    @Test
    void verifyScriptSuccessReturnsNormallyWithOrderedKeysCodeAndTtl() {
        SignupEmailVerificationRequest request = verificationRequest(EMAIL, "123456");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(1L);

        authService.verifySignupEmailCode(request);

        verify(redisTemplate).execute(
                argThat(script -> {
                    String text = script.getScriptAsString();
                    return text.contains("redis.call('GET', KEYS[1])")
                            && text.contains("string.sub(stored, delimiter + 1)")
                            && text.contains("code ~= ARGV[1]")
                            && text.contains("redis.call('DEL', KEYS[1])")
                            && text.contains("redis.call('SET', KEYS[2], stored, 'EX', ARGV[2])");
                }),
                eq(List.of(VERIFICATION_KEY, COMPLETED_KEY)), eq("123456"), eq("300"));
        verify(valueOperations, never()).get(anyString());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void verifyScriptMissingOrExpiredResultThrowsExistingException() {
        SignupEmailVerificationRequest request = verificationRequest(EMAIL, "123456");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(0L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.verifySignupEmailCode(request));

        assertEquals(400, exception.getStatus().value());
        assertTrue(exception.getMessage().contains("만료"));
    }

    @Test
    void verifyScriptMismatchResultThrowsExistingException() {
        SignupEmailVerificationRequest request = verificationRequest(EMAIL, "654321");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(-1L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.verifySignupEmailCode(request));

        assertEquals(400, exception.getStatus().value());
        assertTrue(exception.getMessage().contains("일치하지"));
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
