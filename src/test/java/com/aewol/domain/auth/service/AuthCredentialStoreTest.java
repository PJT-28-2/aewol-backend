package com.aewol.domain.auth.service;

import com.aewol.common.util.JwtUtil;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
    void epochUsesAccessTokenLifetimeAndDeletesRefreshInSameScript() {
        when(jwtUtil.getAccessTokenExpiry()).thenReturn(1_800_000L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(String.class), eq("1800000"))).thenReturn(1L);

        store.advanceEpochAndDeleteRefresh("member-1");

        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(
                scriptCaptor.capture(),
                eq(List.of("auth:epoch:member-1", "refresh:member-1")),
                any(String.class), eq("1800000"));
        String script = scriptCaptor.getValue().getScriptAsString();
        assertTrue(script.contains("SET', KEYS[1]"));
        assertTrue(script.contains("'PX', ARGV[2]"));
        assertTrue(script.contains("DEL', KEYS[2]"));
    }

    @Test
    void recoveryPreparationFailsClosedWhenRedisDoesNotConfirmScript() {
        when(jwtUtil.getAccessTokenExpiry()).thenReturn(1_800_000L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(String.class), eq("1800000"))).thenReturn(null);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> store.advanceEpochAndDeleteRefresh("member-1"));
    }

    @Test
    void loginGetOrCreateEpochIsAtomicAndUsesAccessLifetime() {
        when(jwtUtil.getAccessTokenExpiry()).thenReturn(1_800_000L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(String.class), eq("1800000"))).thenReturn("epoch-1");

        assertEquals("epoch-1", store.getOrCreateEpochForLogin("member-1"));

        ArgumentCaptor<RedisScript<String>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(
                scriptCaptor.capture(), eq(List.of("auth:epoch:member-1")),
                any(String.class), eq("1800000"));
        String script = scriptCaptor.getValue().getScriptAsString();
        assertTrue(script.contains("if currentEpoch then return currentEpoch"));
        assertTrue(script.contains("SET', KEYS[1]"));
        assertTrue(script.contains("'PX', ARGV[2]"));
    }

    @Test
    void loginGetOrCreateFailsClosedWhenRedisReturnsNoEpoch() {
        when(jwtUtil.getAccessTokenExpiry()).thenReturn(1_800_000L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                any(String.class), eq("1800000"))).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> store.getOrCreateEpochForLogin("member-1"));
    }

    @Test
    void credentialWritesRejectMissingEpochBeforeRedisExecution() {
        assertThrows(IllegalArgumentException.class,
                () -> store.storeRefreshIfEpochUnchanged("member-1", null, "refresh-token"));
        assertThrows(IllegalArgumentException.class,
                () -> store.rotateRefreshAtomically("member-1", null, "r1", "r2"));
    }

    @Test
    void loginRefreshWriteRequiresEpochAndSlidesEpochTtlAtomically() {
        when(jwtUtil.getRefreshTokenExpiry()).thenReturn(604_800_000L);
        when(jwtUtil.getAccessTokenExpiry()).thenReturn(1_800_000L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                eq("epoch-1"), eq("refresh-token"), eq("604800000"), eq("1800000")))
                .thenReturn(1L);

        assertTrue(store.storeRefreshIfEpochUnchanged("member-1", "epoch-1", "refresh-token"));

        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(
                scriptCaptor.capture(),
                eq(List.of("auth:epoch:member-1", "refresh:member-1")),
                eq("epoch-1"), eq("refresh-token"), eq("604800000"), eq("1800000"));
        String script = scriptCaptor.getValue().getScriptAsString();
        assertTrue(script.contains("not currentEpoch or currentEpoch ~= ARGV[1]"));
        assertTrue(script.contains("SET', KEYS[2]"));
        assertTrue(script.contains("PEXPIRE', KEYS[1], ARGV[4]"));
    }

    @Test
    void refreshRotationAtomicallyChecksEpochAndPresentedToken() {
        when(jwtUtil.getRefreshTokenExpiry()).thenReturn(604_800_000L);
        when(jwtUtil.getAccessTokenExpiry()).thenReturn(1_800_000L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                eq("epoch-1"), eq("r1"), eq("r2"), eq("604800000"), eq("1800000")))
                .thenReturn(1L, 0L);

        assertTrue(store.rotateRefreshAtomically("member-1", "epoch-1", "r1", "r2"));
        assertFalse(store.rotateRefreshAtomically("member-1", "epoch-1", "r1", "r2"));

        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate, org.mockito.Mockito.times(2)).execute(
                scriptCaptor.capture(),
                eq(List.of("auth:epoch:member-1", "refresh:member-1")),
                eq("epoch-1"), eq("r1"), eq("r2"), eq("604800000"), eq("1800000"));
        String script = scriptCaptor.getAllValues().get(0).getScriptAsString();
        assertTrue(script.contains("currentEpoch ~= ARGV[1]"));
        assertTrue(script.contains("storedToken ~= ARGV[2]"));
        assertTrue(script.contains("SET', KEYS[2]"));
        assertTrue(script.contains("PEXPIRE', KEYS[1], ARGV[5]"));
    }

    @Test
    void readsCurrentEpochFromConventionalKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:epoch:member-1")).thenReturn("epoch-1");

        assertEquals("epoch-1", store.getEpoch("member-1"));
    }
}
