package com.aewol.domain.emergency.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
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

        int result = new HospitalSeedLock(redisTemplate).execute(owned -> 25);

        assertEquals(25, result);
        verify(redisTemplate).execute(
                any(RedisScript.class), eq(List.of("lock:emergency:hospital-seed")), anyString());
    }

    @Test
    @DisplayName("action에 넘겨준 BooleanSupplier는 락 획득 직후 true를 반환한다")
    void should_passOwnedTrue_toActionInitially() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        boolean ownedAtStart = new HospitalSeedLock(redisTemplate).execute(BooleanSupplier::getAsBoolean);

        assertTrue(ownedAtStart);
    }

    @Test
    void should_rejectOverlappingExecution() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        Function<BooleanSupplier, Integer> action = mock(Function.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> new HospitalSeedLock(redisTemplate).execute(action));

        verify(action, never()).apply(any());
    }

    @Test
    @DisplayName("소유 토큰이 일치하면 TTL(밀리초)을 갱신하는 pexpire 스크립트를 실행하고 소유권을 유지한다")
    void should_renewTtl_when_tokenMatchesCurrentOwner() throws Exception {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("lock:emergency:hospital-seed")),
                eq("owner-token"), eq(String.valueOf(Duration.ofMinutes(30).toMillis()))))
                .thenReturn(1L);

        HospitalSeedLock lock = new HospitalSeedLock(redisTemplate);
        AtomicBoolean owned = new AtomicBoolean(true);
        invokeRenew(lock, "owner-token", owned);

        verify(redisTemplate, times(1)).execute(any(RedisScript.class),
                eq(List.of("lock:emergency:hospital-seed")), eq("owner-token"),
                eq(String.valueOf(Duration.ofMinutes(30).toMillis())));
        assertTrue(owned.get());
    }

    @Test
    @DisplayName("[회귀] 소유권을 상실해 스크립트가 0을 반환하면 예외 없이 owned 플래그를 false로 내린다")
    void should_markOwnershipLost_when_renewScriptReturnsZero() throws Exception {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("lock:emergency:hospital-seed")),
                anyString(), anyString()))
                .thenReturn(0L);

        HospitalSeedLock lock = new HospitalSeedLock(redisTemplate);
        AtomicBoolean owned = new AtomicBoolean(true);
        invokeRenew(lock, "stale-token", owned);

        verify(redisTemplate, times(1)).execute(any(RedisScript.class),
                eq(List.of("lock:emergency:hospital-seed")), eq("stale-token"), anyString());
        assertFalse(owned.get(), "renew 스크립트가 0을 반환하면(소유권 상실) owned 플래그가 false여야 한다");
    }

    @Test
    @DisplayName("[회귀] Redis 호출 자체가 실패하면 예외를 전파하지 않되, 소유 여부를 확신할 수 없으므로 owned를 false로 내린다")
    void should_markOwnershipLost_when_redisCallFailsDuringRenew() throws Exception {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("lock:emergency:hospital-seed")),
                anyString(), anyString()))
                .thenThrow(new RuntimeException("connection reset"));

        HospitalSeedLock lock = new HospitalSeedLock(redisTemplate);
        AtomicBoolean owned = new AtomicBoolean(true);
        invokeRenew(lock, "owner-token", owned);

        assertFalse(owned.get(), "연장 여부를 확인할 수 없는 상황은 안전하게 소유권 상실로 간주해야 한다");
    }

    private void invokeRenew(HospitalSeedLock lock, String token, AtomicBoolean owned) throws Exception {
        Method renew = HospitalSeedLock.class.getDeclaredMethod("renew", String.class, AtomicBoolean.class);
        renew.setAccessible(true);
        renew.invoke(lock, token, owned);
    }
}
