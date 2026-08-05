package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.JwtUtil;
import com.aewol.domain.auth.dto.SignupEmailCodeRequest;
import com.aewol.domain.auth.dto.SignupEmailCodeResponse;
import com.aewol.domain.auth.dto.SignupEmailVerificationRequest;
import com.aewol.domain.member.mapper.MemberMapper;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplEmailVerificationTest {

    @Mock MemberMapper memberMapper;
    @Mock WalletMapper walletMapper;
    @Mock JwtUtil jwtUtil;
    @Mock PasswordEncoder passwordEncoder;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock EmailService emailService;
    @Mock KakaoAuthClient kakaoAuthClient;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                memberMapper, walletMapper, jwtUtil, passwordEncoder,
                redisTemplate, emailService, kakaoAuthClient);
    }

    @Test
    void sendsSixDigitCodeAndStoresItForThreeHundredSeconds() {
        SignupEmailCodeRequest request = emailCodeRequest("newuser@aewol.com");
        when(memberMapper.existsActiveByEmail(request.getEmail())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        SignupEmailCodeResponse response = authService.sendSignupVerificationCode(request);

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.eq("verify:newuser@aewol.com"),
                codeCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(300L),
                org.mockito.ArgumentMatchers.eq(TimeUnit.SECONDS));
        assertTrue(codeCaptor.getValue().matches("\\d{6}"));
        verify(emailService).sendVerificationEmail("newuser@aewol.com", codeCaptor.getValue());
        assertEquals(300L, response.getExpiresInSeconds());
    }

    @Test
    void rejectsEmailUsedByActiveMember() {
        SignupEmailCodeRequest request = emailCodeRequest("active@aewol.com");
        when(memberMapper.existsActiveByEmail(request.getEmail())).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.sendSignupVerificationCode(request));

        assertEquals(409, exception.getStatus().value());
        verify(valueOperations, never()).set(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(TimeUnit.class));
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    void resendingOverwritesCodeAndRefreshesTtl() {
        SignupEmailCodeRequest request = emailCodeRequest("retry@aewol.com");
        when(memberMapper.existsActiveByEmail(request.getEmail())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        authService.sendSignupVerificationCode(request);
        authService.sendSignupVerificationCode(request);

        verify(valueOperations, times(2)).set(
                org.mockito.ArgumentMatchers.eq("verify:retry@aewol.com"),
                anyString(),
                org.mockito.ArgumentMatchers.eq(300L),
                org.mockito.ArgumentMatchers.eq(TimeUnit.SECONDS));
        verify(emailService, times(2)).sendVerificationEmail(
                org.mockito.ArgumentMatchers.eq("retry@aewol.com"), anyString());
    }

    @Test
    void deletesStoredCodeWhenEmailSendingFails() {
        SignupEmailCodeRequest request = emailCodeRequest("failure@aewol.com");
        when(memberMapper.existsActiveByEmail(request.getEmail())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RuntimeException("smtp failure"))
                .when(emailService).sendVerificationEmail(anyString(), anyString());

        assertThrows(RuntimeException.class, () -> authService.sendSignupVerificationCode(request));

        verify(redisTemplate).delete("verify:failure@aewol.com");
    }

    @Test
    void verifiesCodeStoresCompletedStateAndDeletesCode() {
        SignupEmailVerificationRequest request = verificationRequest("newuser@aewol.com", "123456");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("verify:newuser@aewol.com")).thenReturn("123456");

        authService.verifySignupEmailCode(request);

        verify(valueOperations).set(
                "verify:completed:newuser@aewol.com", "true", 300L, TimeUnit.SECONDS);
        verify(redisTemplate).delete("verify:newuser@aewol.com");
    }

    @Test
    void rejectsMissingOrExpiredCode() {
        SignupEmailVerificationRequest request = verificationRequest("newuser@aewol.com", "123456");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("verify:newuser@aewol.com")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.verifySignupEmailCode(request));

        assertEquals(400, exception.getStatus().value());
        assertTrue(exception.getMessage().contains("만료"));
        verify(valueOperations, never()).set(
                org.mockito.ArgumentMatchers.eq("verify:completed:newuser@aewol.com"),
                anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class));
    }

    @Test
    void rejectsIncorrectCode() {
        SignupEmailVerificationRequest request = verificationRequest("newuser@aewol.com", "654321");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("verify:newuser@aewol.com")).thenReturn("123456");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.verifySignupEmailCode(request));

        assertEquals(400, exception.getStatus().value());
        assertTrue(exception.getMessage().contains("일치하지"));
        verify(redisTemplate, never()).delete("verify:newuser@aewol.com");
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
