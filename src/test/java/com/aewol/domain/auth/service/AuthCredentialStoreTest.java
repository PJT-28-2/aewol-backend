package com.aewol.domain.auth.service;

import com.aewol.common.util.JwtUtil;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthCredentialStoreTest {

    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock JwtUtil jwtUtil;
    private AuthCredentialStore store;

    @BeforeEach
    void setUp() {
        store = new AuthCredentialStore(redisTemplate, jwtUtil);
    }

    @Test
    void storesRefreshWithConfiguredTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(jwtUtil.getRefreshTokenExpiry()).thenReturn(604_800_000L);

        store.storeRefresh("member-1", "r1");

        verify(valueOperations).set("refresh:member-1", "r1", 604_800_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void refreshRotationSupportsR1ToR2RejectsR1ReuseAndSupportsR2ToR3() {
        when(jwtUtil.getRefreshTokenExpiry()).thenReturn(604_800_000L);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("refresh:member-1")),
                eq("r1"), eq("r2"), eq("604800000"))).thenReturn(1L, 0L);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("refresh:member-1")),
                eq("r2"), eq("r3"), eq("604800000"))).thenReturn(1L);

        assertTrue(store.rotateRefreshAtomically("member-1", "r1", "r2"));
        assertFalse(store.rotateRefreshAtomically("member-1", "r1", "r2"));
        assertTrue(store.rotateRefreshAtomically("member-1", "r2", "r3"));

        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate, org.mockito.Mockito.times(2)).execute(
                scriptCaptor.capture(), eq(List.of("refresh:member-1")),
                eq("r1"), eq("r2"), eq("604800000"));
        String script = scriptCaptor.getAllValues().get(0).getScriptAsString();
        assertTrue(script.contains("storedToken ~= ARGV[1]"));
        assertTrue(script.contains("SET', KEYS[1], ARGV[2]"));
    }

    @Test
    void deletesOnlyRefreshCredential() {
        store.deleteRefresh("member-1");

        verify(redisTemplate).delete("refresh:member-1");
    }
}
