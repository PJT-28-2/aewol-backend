package com.aewol.domain.emergency.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class HospitalSeedLockTest {

    @Test
    void should_releaseLockOwnedByCurrentExecution() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        int result = new HospitalSeedLock(redisTemplate).execute(() -> 25);

        assertEquals(25, result);
        verify(redisTemplate).execute(
                any(RedisScript.class), eq(List.of("lock:emergency:hospital-seed")), anyString());
    }

    @Test
    void should_rejectOverlappingExecution() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        Supplier<Integer> action = mock(Supplier.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> new HospitalSeedLock(redisTemplate).execute(action));

        verify(action, never()).get();
    }

    @Test
    @DisplayName("소유 토큰이 일치하면 TTL(밀리초)을 갱신하는 pexpire 스크립트를 실행한다")
    void should_renewTtl_when_tokenMatchesCurrentOwner() throws Exception {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("lock:emergency:hospital-seed")),
                eq("owner-token"), eq(String.valueOf(Duration.ofMinutes(30).toMillis()))))
                .thenReturn(1L);

        HospitalSeedLock lock = new HospitalSeedLock(redisTemplate);
        invokeRenew(lock, "owner-token");

        verify(redisTemplate, times(1)).execute(any(RedisScript.class),
                eq(List.of("lock:emergency:hospital-seed")), eq("owner-token"),
                eq(String.valueOf(Duration.ofMinutes(30).toMillis())));
    }

    @Test
    @DisplayName("소유권을 상실해 스크립트가 0을 반환해도 예외 없이 넘어간다")
    void should_notThrow_when_renewFailsBecauseOwnershipLost() throws Exception {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("lock:emergency:hospital-seed")),
                anyString(), anyString()))
                .thenReturn(0L);

        HospitalSeedLock lock = new HospitalSeedLock(redisTemplate);
        invokeRenew(lock, "stale-token");

        verify(redisTemplate, times(1)).execute(any(RedisScript.class),
                eq(List.of("lock:emergency:hospital-seed")), eq("stale-token"), anyString());
    }

    @Test
    @DisplayName("Redis 호출 자체가 실패해도 renew()는 예외를 전파하지 않는다")
    void should_notThrow_when_redisCallFailsDuringRenew() throws Exception {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("lock:emergency:hospital-seed")),
                anyString(), anyString()))
                .thenThrow(new RuntimeException("connection reset"));

        HospitalSeedLock lock = new HospitalSeedLock(redisTemplate);
        invokeRenew(lock, "owner-token");
    }

    private void invokeRenew(HospitalSeedLock lock, String token) throws Exception {
        Method renew = HospitalSeedLock.class.getDeclaredMethod("renew", String.class);
        renew.setAccessible(true);
        renew.invoke(lock, token);
    }
}
