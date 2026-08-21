package com.aewol.batch;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
