package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class KakaoPhoneVerificationStoreTest {

    private static final String TOKEN_HASH = "a".repeat(64);
    private static final String REGISTRATION_KEY =
            "kakao:registration:80383f974f22964fd6b7ae851b6ccc9180ed4e6fcb2e415bafcab6d822139238";

    @Mock RedisTemplate<String, String> redisTemplate;
    private KakaoPhoneVerificationStore store;

    @BeforeEach
    void setUp() {
        store = new KakaoPhoneVerificationStore(redisTemplate);
    }

    @Test
    void issuesSecureSixDigitCodeWithFiveMinuteTtlAndHashedOtpKey() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), eq("300000")))
                .thenReturn(300_000L);

        KakaoPhoneVerificationStore.IssuedVerification issued =
                store.issue(REGISTRATION_KEY, TOKEN_HASH, "01012345678");

        assertTrue(issued.getCode().matches("\\d{6}"));
        assertEquals(300L, issued.getExpiresInSeconds());
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(),
                eq(issued.getStoredValue()), eq("300000"));
        assertEquals(REGISTRATION_KEY, keys.getValue().get(0));
        assertEquals("kakao:registration:phone:verify:" + TOKEN_HASH,
                keys.getValue().get(1));
        assertFalse(keys.getValue().get(1).contains("t".repeat(43)));
    }

    @Test
    void otpTtlNeverExceedsRemainingRegistrationTtl() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), eq("300000")))
                .thenReturn(42_001L);

        KakaoPhoneVerificationStore.IssuedVerification issued =
                store.issue(REGISTRATION_KEY, TOKEN_HASH, "01012345678");

        assertEquals(43L, issued.getExpiresInSeconds());
        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(script.capture(), anyList(), anyString(), eq("300000"));
        assertTrue(script.getValue().getScriptAsString().contains("PTTL"));
        assertTrue(script.getValue().getScriptAsString().contains("sessionTtl < otpTtl"));
    }

    @Test
    void resendAtomicallyReplacesPreviousOtpAtSameKey() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), eq("300000")))
                .thenReturn(300_000L);

        store.issue(REGISTRATION_KEY, TOKEN_HASH, "01012345678");
        store.issue(REGISTRATION_KEY, TOKEN_HASH, "01012345678");

        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate, times(2)).execute(
                script.capture(), anyList(), anyString(), eq("300000"));
        assertTrue(script.getAllValues().get(0).getScriptAsString()
                .contains("redis.call('SET', KEYS[2], ARGV[1]"));
    }

    @Test
    void verifiesOtpAndKeepsPhoneInsideRedisState() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("123456"), eq("5")))
                .thenReturn("OK|01012345678");

        String phone = store.verify(TOKEN_HASH, "123456");

        assertEquals("01012345678", phone);
        ArgumentCaptor<RedisScript<String>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(script.capture(), anyList(), eq("123456"), eq("5"));
        String lua = script.getValue().getScriptAsString();
        assertTrue(lua.contains("attempts >= tonumber(ARGV[2])"));
        assertTrue(lua.contains("'VERIFIED|' .. phone"));
    }

    @Test
    void firstFourFailuresRetainOtpAndFifthFailureDiscardsIt() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), eq("5")))
                .thenReturn(
                        "MISMATCH_RETAINED",
                        "MISMATCH_RETAINED",
                        "MISMATCH_RETAINED",
                        "MISMATCH_RETAINED",
                        "MISMATCH_DISCARDED");

        for (int index = 0; index < 5; index++) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> store.verify(TOKEN_HASH, "123456"));
            assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        }

        ArgumentCaptor<RedisScript<String>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate, times(5)).execute(
                script.capture(), anyList(), eq("123456"), eq("5"));
        String lua = script.getValue().getScriptAsString();
        assertTrue(lua.contains("attempts >= tonumber(ARGV[2])"));
        assertTrue(lua.contains("redis.call('DEL', KEYS[1])"));
        assertTrue(lua.contains("return 'MISMATCH_DISCARDED'"));
        assertTrue(lua.contains("'KEEPTTL'"));
        assertTrue(lua.contains("return 'MISMATCH_RETAINED'"));
    }

    @Test
    void missingAndMalformedStateUseSameBadRequestContract() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), eq("5")))
                .thenReturn("MISSING", "INVALID");

        for (int index = 0; index < 2; index++) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> store.verify(TOKEN_HASH, "123456"));
            assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        }
    }

    @Test
    void lateDiscardCannotDeleteNewerIssueEvenWhenOtpCodesMatch() {
        SecureRandom random = mock(SecureRandom.class);
        when(random.nextInt(1_000_000)).thenReturn(123456, 123456);
        AtomicInteger nonceGeneration = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            byte[] nonce = invocation.getArgument(0);
            Arrays.fill(nonce, (byte) nonceGeneration.incrementAndGet());
            return null;
        }).when(random).nextBytes(any(byte[].class));
        store = new KakaoPhoneVerificationStore(redisTemplate, random);

        AtomicReference<String> currentState = new AtomicReference<>();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), eq("300000")))
                .thenAnswer(invocation -> {
                    currentState.set(invocation.getArgument(2));
                    return 300_000L;
                });
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenAnswer(invocation -> {
                    String expected = invocation.getArgument(2);
                    return currentState.compareAndSet(expected, null) ? 1L : 0L;
                });

        KakaoPhoneVerificationStore.IssuedVerification first =
                store.issue(REGISTRATION_KEY, TOKEN_HASH, "01012345678");
        KakaoPhoneVerificationStore.IssuedVerification second =
                store.issue(REGISTRATION_KEY, TOKEN_HASH, "01012345678");

        assertEquals(first.getCode(), second.getCode());
        assertNotEquals(first.getStoredValue(), second.getStoredValue());
        assertEquals(4, first.getStoredValue().split("\\|", -1).length);
        assertEquals(4, second.getStoredValue().split("\\|", -1).length);

        store.discard(first);
        assertEquals(second.getStoredValue(), currentState.get());

        store.discard(second);
        assertEquals(null, currentState.get());
    }

    @Test
    void claimedOrExpiredRegistrationSessionCannotReceiveOtp() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), eq("300000")))
                .thenReturn(-2L, -1L);

        BusinessException claimed = assertThrows(BusinessException.class,
                () -> store.issue(REGISTRATION_KEY, TOKEN_HASH, "01012345678"));
        BusinessException expired = assertThrows(BusinessException.class,
                () -> store.issue(REGISTRATION_KEY, TOKEN_HASH, "01012345678"));

        assertEquals(HttpStatus.CONFLICT, claimed.getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, expired.getStatus());
    }

    @Test
    void redisFailureIsConvertedToServiceUnavailableWithoutInternalMessage() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), eq("300000")))
                .thenThrow(new RuntimeException("raw redis key and value"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> store.issue(REGISTRATION_KEY, TOKEN_HASH, "01012345678"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertFalse(exception.getMessage().contains("redis"));
    }
}
