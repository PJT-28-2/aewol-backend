package com.aewol.domain.transaction.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

/**
 * TossPaymentClaim의 멱등성 보장을 검증한다. 특히 성공한 클레임이 delete를 호출하지 않는다는
 * 사실이 핵심 행위 검증이다 — Gov24SyncLock처럼 finally에서 해제해버리면 순차 재시도(브라우저
 * 새로고침/뒤로가기)를 전혀 막지 못하는 뮤텍스로 퇴화하기 때문이다.
 */
class TossPaymentClaimTest {

    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private TossPaymentClaim claim;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        claim = new TossPaymentClaim(redisTemplate);
    }

    @Test
    @DisplayName("잠금 확보에 성공하면 예외 없이 반환한다")
    void should_returnNormally_when_acquisitionSucceeds() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        assertDoesNotThrow(() -> claim.acquire("member-1", "order-1"));
    }

    @Test
    @DisplayName("잠금 확보가 경합에서 실패하면 CONFLICT를 던진다")
    void should_throwConflict_when_acquisitionContended() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claim.acquire("member-1", "order-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName("Redis 장애 시 SERVICE_UNAVAILABLE로 실패한다(fail-closed, Toss를 호출하지 않는다)")
    void should_throwServiceUnavailable_when_redisFails() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), any(TimeUnit.class)))
                .thenThrow(new RedisConnectionFailureException("Redis 연결 실패"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claim.acquire("member-1", "order-1"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
    }

    @Test
    @DisplayName("클레임 확보 성공은 delete를 호출하지 않는다 - TTL 만료로만 소멸한다")
    void should_notDeleteKey_when_acquisitionSucceeds() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        claim.acquire("member-1", "order-1");

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("release를 명시적으로 호출하면 해당 키를 delete한다")
    void should_deleteKey_when_releaseCalledExplicitly() {
        claim.release("member-1", "order-1");

        verify(redisTemplate).delete(anyString());
    }
}
