package com.aewol.external.codef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.RedisRateLimiter;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;

/**
 * requestAccountTransferAuth가 "잠금 확보 -> 요청 횟수 증가" 순서로 동작하는지 검증한다.
 * 순서가 반대면 동시에 들어온 재시도들이 잠금 실패(CONFLICT)로 끝나면서도 30분 요청 횟수를
 * 매번 소비해서, 자동 재시도가 반복되면 실제 CODEF 호출 없이 한도(5회)에 도달할 수 있다는
 * CodeRabbit 지적(2026-08-07)에 대한 회귀 방지 테스트.
 */
class CodefClientTransferAuthTest {

    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisRateLimiter redisRateLimiter;
    private CodefClient codefClient;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        RestTemplate codefRestTemplate = mock(RestTemplate.class);
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        redisRateLimiter = mock(RedisRateLimiter.class);
        Environment environment = mock(Environment.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        codefClient = new CodefClient(codefRestTemplate, redisTemplate, redisRateLimiter, environment);
    }

    @Test
    @DisplayName("잠금 확보에 실패(CONFLICT)하면 요청 횟수 카운터를 증가시키지 않는다")
    void should_notIncrementRequestCount_when_lockNotAcquired() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> codefClient.requestAccountTransferAuth("004", "110-123-456789"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(redisRateLimiter, never()).incrementWithExpiry(anyString(), anyLong());
    }

    @Test
    @DisplayName("잠금은 확보했지만 요청 횟수 한도를 넘기면 TOO_MANY_REQUESTS를 던지고 잠금을 즉시 해제한다")
    void should_releaseLock_when_rateLimitExceededAfterLockAcquired() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(redisRateLimiter.incrementWithExpiry(anyString(), anyLong())).thenReturn(6L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> codefClient.requestAccountTransferAuth("004", "110-123-456789"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        verify(redisTemplate).delete(anyString());
    }
}
