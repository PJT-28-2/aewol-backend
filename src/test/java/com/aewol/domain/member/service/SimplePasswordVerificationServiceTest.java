package com.aewol.domain.member.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.member.mapper.MemberMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class SimplePasswordVerificationServiceTest {
    @Mock MemberMapper memberMapper;
    @Mock PasswordEncoder passwordEncoder;
    @Mock RedisRateLimiter redisRateLimiter;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    private SimplePasswordVerificationService service;

    @BeforeEach
    void setUp() {
        service = new SimplePasswordVerificationService(
                memberMapper, passwordEncoder, redisRateLimiter, redisTemplate);
    }

    @Test
    void should_returnTrueAndClearFailures_when_passwordMatches() {
        when(memberMapper.findById("1")).thenReturn(memberWithPin("encoded"));
        when(passwordEncoder.matches("482913", "encoded")).thenReturn(true);

        assertTrue(service.verify("1", "482913"));

        verify(redisTemplate).delete("simple-password:failures:1");
        verify(redisRateLimiter, never()).incrementWithExpiry("simple-password:failures:1", 60);
    }

    @Test
    void should_returnFalse_when_passwordMismatchIsBelowLimit() {
        when(memberMapper.findById("1")).thenReturn(memberWithPin("encoded"));
        when(passwordEncoder.matches("000000", "encoded")).thenReturn(false);
        when(redisRateLimiter.incrementWithExpiry("simple-password:failures:1", 60)).thenReturn(4L);

        assertFalse(service.verify("1", "000000"));
    }

    @Test
    void should_lockForSixtySeconds_when_fifthFailureOccurs() {
        when(memberMapper.findById("1")).thenReturn(memberWithPin("encoded"));
        when(passwordEncoder.matches("000000", "encoded")).thenReturn(false);
        when(redisRateLimiter.incrementWithExpiry("simple-password:failures:1", 60)).thenReturn(5L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.verify("1", "000000"));

        assertEquals(429, exception.getStatus().value());
        verify(valueOperations).set(eq("simple-password:lock:1"), eq("1"), eq(Duration.ofSeconds(60)));
        verify(redisTemplate).delete("simple-password:failures:1");
    }

    @Test
    void should_rejectBeforePasswordCheck_when_memberIsLocked() {
        when(redisTemplate.hasKey("simple-password:lock:1")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.verify("1", "482913"));

        assertEquals(429, exception.getStatus().value());
        verify(memberMapper, never()).findById("1");
    }

    @Test
    void should_requirePasswordSetup_when_memberHasNoPin() {
        when(memberMapper.findById("1")).thenReturn(new HashMap<>());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.verify("1", "482913"));

        assertEquals(409, exception.getStatus().value());
    }

    @Test
    void should_returnServiceUnavailable_when_lockLookupFails() {
        when(redisTemplate.hasKey("simple-password:lock:1"))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));

        assertServiceUnavailable(() -> service.verify("1", "482913"));
        verify(memberMapper, never()).findById("1");
    }

    @Test
    void should_returnServiceUnavailable_when_lockLookupReturnsAbnormalNull() {
        when(redisTemplate.hasKey("simple-password:lock:1")).thenReturn(null);

        assertServiceUnavailable(() -> service.verify("1", "482913"));
        verify(memberMapper, never()).findById("1");
    }

    @Test
    void should_returnServiceUnavailable_when_failureIncrementFails() {
        when(memberMapper.findById("1")).thenReturn(memberWithPin("encoded"));
        when(passwordEncoder.matches("000000", "encoded")).thenReturn(false);
        when(redisRateLimiter.incrementWithExpiry("simple-password:failures:1", 60))
                .thenThrow(new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "redis unavailable"));

        assertServiceUnavailable(() -> service.verify("1", "000000"));
    }

    @Test
    void should_returnServiceUnavailable_when_lockStoreFails() {
        when(memberMapper.findById("1")).thenReturn(memberWithPin("encoded"));
        when(passwordEncoder.matches("000000", "encoded")).thenReturn(false);
        when(redisRateLimiter.incrementWithExpiry("simple-password:failures:1", 60)).thenReturn(5L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RedisConnectionFailureException("redis unavailable")).when(valueOperations)
                .set("simple-password:lock:1", "1", Duration.ofSeconds(60));

        assertServiceUnavailable(() -> service.verify("1", "000000"));
        verify(redisTemplate, never()).delete("simple-password:failures:1");
    }

    @Test
    void should_returnServiceUnavailable_when_requiredFailureCleanupFails() {
        when(memberMapper.findById("1")).thenReturn(memberWithPin("encoded"));
        when(passwordEncoder.matches("482913", "encoded")).thenReturn(true);
        when(redisTemplate.delete("simple-password:failures:1"))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));

        assertServiceUnavailable(() -> service.verify("1", "482913"));
    }

    private void assertServiceUnavailable(org.junit.jupiter.api.function.Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertEquals(503, exception.getStatus().value());
        assertEquals(null, exception.getErrorCode());
    }

    private Map<String, Object> memberWithPin(String encodedPassword) {
        Map<String, Object> member = new HashMap<>();
        member.put("simple_password", encodedPassword);
        return member;
    }
}
