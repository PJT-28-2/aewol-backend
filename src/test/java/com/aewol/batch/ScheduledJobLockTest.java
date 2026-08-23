package com.aewol.batch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class ScheduledJobLockTest {

    @Test
    void should_runActionAndReleaseLock_whenLockIsAcquired() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        Runnable action = mock(Runnable.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("lock:batch:recurring-payment"), anyString(), any(Duration.class)))
                .thenReturn(true);

        boolean ran = new ScheduledJobLock(redisTemplate)
                .runExclusive("lock:batch:recurring-payment", Duration.ofHours(1), action);

        assertTrue(ran);
        verify(action).run();
        verify(redisTemplate).execute(
                any(RedisScript.class), eq(List.of("lock:batch:recurring-payment")), anyString());
    }

    @Test
    void should_skipAction_whenLockIsHeld() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        Runnable action = mock(Runnable.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        boolean ran = new ScheduledJobLock(redisTemplate)
                .runExclusive("lock:batch:recurring-payment", Duration.ofHours(1), action);

        assertFalse(ran);
        verify(action, never()).run();
        verify(redisTemplate, never()).execute(any(RedisScript.class), any(), any());
    }

    /*
     * 락은 finally에서 푼다. 그 사실이 깨지면 정기결제 같은 배치가 TTL(최대 1시간)
     * 동안 통째로 막힌다. 예외가 나는 경로는 정작 테스트가 없었다.
     */
    @Test
    void should_releaseLock_evenWhenActionThrows() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        Runnable exploding = () -> {
            throw new IllegalStateException("배치 실패");
        };

        ScheduledJobLock lock = new ScheduledJobLock(redisTemplate);

        // 예외는 그대로 올려보낸다. 삼키면 실패한 배치가 성공한 것처럼 보인다.
        assertThrows(IllegalStateException.class,
                () -> lock.runExclusive("lock:batch:recurring-payment", Duration.ofHours(1), exploding));

        verify(redisTemplate).execute(
                any(RedisScript.class), eq(List.of("lock:batch:recurring-payment")), anyString());
    }

    /*
     * Redis가 죽으면 다른 인스턴스가 도는 중인지 알 방법이 없다. 모르는 채로 실행하면
     * 정기결제가 이중 출금될 수 있으므로 한 주기를 거른다.
     */
    @Test
    void should_skipAction_whenRedisFails() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        Runnable action = mock(Runnable.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        boolean ran = new ScheduledJobLock(redisTemplate)
                .runExclusive("lock:batch:recurring-payment", Duration.ofHours(1), action);

        assertFalse(ran);
        verify(action, never()).run();
        // 잡지도 못한 락을 푸는 스크립트를 돌리면 남의 락을 건드릴 수 있다.
        verify(redisTemplate, never()).execute(any(RedisScript.class), any(), anyString());
    }
}
