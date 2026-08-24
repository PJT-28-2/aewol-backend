package com.aewol.domain.auth.service;

import com.aewol.common.cache.MemberAuthStateCache;
import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.JwtUtil;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.common.util.Sha256Util;
import com.aewol.domain.auth.dto.LoginRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.domain.notification.mapper.NotificationSettingMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.kakao.KakaoAuthClient;
import com.aewol.external.smtp.EmailService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplLoginRateLimitTest {

    private static final String EMAIL = "member@example.com";
    private static final String EMAIL_HASH = Sha256Util.lowercaseHex(EMAIL);
    private static final String FAILURE_KEY = "auth:login:failures:email:" + EMAIL_HASH;
    private static final String LOCK_KEY = "auth:login:lock:email:" + EMAIL_HASH;

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

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(memberMapper, walletMapper, notificationSettingMapper,
                jwtUtil, passwordEncoder, redisTemplate, redisRateLimiter, emailService,
                kakaoAuthClient, authCredentialStore, kakaoRegistrationStore,
                transactionOperations,
                MemberAuthStateCache.withoutCache(memberMapper));
    }

    @Test
    void unknownEmailAndWrongPasswordCountTowardTheSameLimit() {
        LoginRequest request = loginRequest();
        when(memberMapper.findActiveByEmail(EMAIL)).thenReturn(null);
        when(redisRateLimiter.incrementWithExpiry(FAILURE_KEY, 900L)).thenReturn(1L);

        BusinessException missing = assertThrows(BusinessException.class, () -> service.login(request));

        assertEquals(401, missing.getStatus().value());
        assertEquals("이메일 또는 비밀번호가 잘못되었습니다.", missing.getMessage());
        verify(passwordEncoder, never()).matches(eq("password"), eq(null));
    }

    @Test
    void fifthFailureLocksLoginForFifteenMinutes() {
        LoginRequest request = loginRequest();
        when(memberMapper.findActiveByEmail(EMAIL)).thenReturn(null);
        when(redisRateLimiter.incrementWithExpiry(FAILURE_KEY, 900L)).thenReturn(5L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.login(request));

        assertEquals(429, exception.getStatus().value());
        assertEquals("로그인 시도가 너무 많습니다. 15분 후 다시 시도해주세요.", exception.getMessage());
        verify(valueOperations).set(eq(LOCK_KEY), eq("1"), eq(Duration.ofSeconds(900)));
        verify(redisTemplate).delete(FAILURE_KEY);
    }

    @Test
    void lockedEmailIsRejectedBeforePasswordCheck() {
        when(redisTemplate.hasKey(LOCK_KEY)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.login(loginRequest()));

        assertEquals(429, exception.getStatus().value());
        verify(memberMapper, never()).findActiveByEmail(EMAIL);
        verify(passwordEncoder, never()).matches(eq("password"), eq("encoded"));
    }

    @Test
    void successfulLoginClearsFailureAndLockKeys() {
        LoginRequest request = loginRequest();
        Map<String, Object> member = new HashMap<>();
        member.put("member_id", 7L);
        member.put("password", "encoded");
        member.put("email_verified", "Y");
        member.put("role", "USER");
        when(memberMapper.findActiveByEmail(EMAIL)).thenReturn(member);
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);
        when(jwtUtil.generateAccessToken("7", "USER")).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken("7")).thenReturn("refresh-token");

        service.login(request);

        verify(redisTemplate).delete(FAILURE_KEY);
        verify(redisTemplate).delete(LOCK_KEY);
        verify(authCredentialStore).storeRefresh("7", "refresh-token");
    }

    private LoginRequest loginRequest() {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "email", EMAIL);
        ReflectionTestUtils.setField(request, "password", "password");
        return request;
    }
}
