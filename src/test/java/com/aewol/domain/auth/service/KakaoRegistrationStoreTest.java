package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.auth.dto.KakaoRegistrationSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class KakaoRegistrationStoreTest {

    private static final String TOKEN_A = "a".repeat(43);
    private static final String TOKEN_A_HASH =
            "66d34fba71f8f450f7e45598853e53bfc23bbd129027cbb131a2f4ffd7878cd0";

    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KakaoRegistrationStore store;

    @BeforeEach
    void setUp() {
        store = new KakaoRegistrationStore(redisTemplate);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void storesMinimalJsonWithOpaque256BitTokenAndFifteenMinuteTtl() throws Exception {
        when(valueOperations.setIfAbsent(
                anyString(), anyString(), eq(900L), eq(TimeUnit.SECONDS))).thenReturn(true);

        String registrationToken = store.create(
                new KakaoRegistrationSession("123456789", "member@example.com", "홍길동"));

        assertTrue(registrationToken.matches("[A-Za-z0-9_-]{43}"));
        assertFalse(registrationToken.contains("123456789"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(
                keyCaptor.capture(), valueCaptor.capture(), eq(900L), eq(TimeUnit.SECONDS));
        assertEquals("kakao:registration:" + sha256(registrationToken), keyCaptor.getValue());
        assertFalse(keyCaptor.getValue().contains(registrationToken));

        JsonNode session = objectMapper.readTree(valueCaptor.getValue());
        assertEquals(3, session.size());
        assertEquals("123456789", session.get("providerId").asText());
        assertEquals("member@example.com", session.get("email").asText());
        assertEquals("홍길동", session.get("name").asText());
        assertFalse(session.has("accessToken"));
        assertFalse(session.has("refreshToken"));
        assertFalse(session.has("authorizationCode"));
    }

    @Test
    void redisFailureIsConvertedToServiceUnavailableWithoutReturningToken() {
        when(valueOperations.setIfAbsent(
                anyString(), anyString(), eq(900L), eq(TimeUnit.SECONDS)))
                .thenThrow(new RuntimeException("redis key and token details"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> store.create(session()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals("카카오 가입을 진행할 수 없습니다. 잠시 후 다시 시도해주세요.",
                exception.getMessage());
        assertFalse(exception.getMessage().contains("redis"));
        assertFalse(exception.getMessage().contains("token"));
    }

    @Test
    void retriesWithNewTokenAfterSetIfAbsentReturnsFalse() {
        when(valueOperations.setIfAbsent(
                anyString(), anyString(), eq(900L), eq(TimeUnit.SECONDS)))
                .thenReturn(false, true);

        String registrationToken = store.create(session());

        assertTrue(registrationToken.matches("[A-Za-z0-9_-]{43}"));
        verify(valueOperations, times(2)).setIfAbsent(
                anyString(), anyString(), eq(900L), eq(TimeUnit.SECONDS));
    }

    @Test
    void nullSetIfAbsentResultIsRetried() {
        when(valueOperations.setIfAbsent(
                anyString(), anyString(), eq(900L), eq(TimeUnit.SECONDS)))
                .thenReturn(null, true);

        String registrationToken = store.create(session());

        assertTrue(registrationToken.matches("[A-Za-z0-9_-]{43}"));
        verify(valueOperations, times(2)).setIfAbsent(
                anyString(), anyString(), eq(900L), eq(TimeUnit.SECONDS));
    }

    @Test
    void threeTokenCollisionsFailWithoutReturningRegistrationToken() {
        when(valueOperations.setIfAbsent(
                anyString(), anyString(), eq(900L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> store.create(session()));

        assertEquals("카카오 가입 세션 토큰을 발급할 수 없습니다.", exception.getMessage());
        verify(valueOperations, times(3)).setIfAbsent(
                anyString(), anyString(), eq(900L), eq(TimeUnit.SECONDS));
    }

    @Test
    void readsVerifiedSessionAndRejectsMalformedJson() {
        String token = TOKEN_A;
        when(valueOperations.get("kakao:registration:" + TOKEN_A_HASH))
                .thenReturn("{\"providerId\":\"123\",\"email\":\"a@example.com\","
                                + "\"name\":\"홍길동\",\"verifiedPhone\":\"01012345678\"}",
                        "{malformed");

        KakaoRegistrationSession session = store.getAvailable(token);
        BusinessException malformed = assertThrows(BusinessException.class,
                () -> store.getAvailable(token));

        assertEquals("01012345678", session.getVerifiedPhone());
        assertEquals(HttpStatus.BAD_REQUEST, malformed.getStatus());
    }

    @Test
    void verifiedPhoneUpdateUsesKeepTtlAndDoesNotWriteAnotherSessionField() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("01012345678")))
                .thenReturn("OK");

        store.updateVerifiedPhone(TOKEN_A, "01012345678");

        ArgumentCaptor<RedisScript<String>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(scriptCaptor.capture(),
                eq(java.util.List.of("kakao:registration:" + TOKEN_A_HASH)),
                eq("01012345678"));
        String script = scriptCaptor.getValue().getScriptAsString();
        assertTrue(script.contains("session['verifiedPhone'] = ARGV[1]"));
        assertTrue(script.contains("KEEPTTL"));
        assertFalse(script.contains("accessToken"));
        assertFalse(script.contains("refreshToken"));
        assertFalse(script.contains("authorizationCode"));
    }

    @Test
    void claimIsAtomicAndReturnsOriginalSession() {
        String token = "b".repeat(43);
        String json = "{\"providerId\":\"123\",\"email\":\"a@example.com\","
                + "\"name\":\"홍길동\",\"verifiedPhone\":\"01012345678\"}";
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn("OK|" + json);

        KakaoRegistrationStore.Claim claim = store.claim(token);

        assertEquals("01012345678", claim.getSession().getVerifiedPhone());
        assertEquals(json, claim.getOriginalValue());
        assertTrue(claim.getClaimedValue().startsWith("CLAIMED|"));
        assertEquals("kakao:registration:" + sha256(token), claim.getKey());
        assertFalse(claim.getKey().contains(token));
    }

    @Test
    void sameRegistrationTokenAlwaysMapsToSameDigestKey() {
        String first = store.redisKey(TOKEN_A);
        String second = store.redisKey(TOKEN_A);

        assertEquals("kakao:registration:" + TOKEN_A_HASH, first);
        assertEquals(first, second);
        assertFalse(first.contains(TOKEN_A));
    }

    @Test
    void concurrentClaimIsRejectedWithConflict() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn("CLAIMED");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> store.claim("c".repeat(43)));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    @Test
    void missingClaimIsRejectedAsBadRequest() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn("MISSING");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> store.claim("f".repeat(43)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void completionDeletesOnlyMatchingClaimAndRollbackRestoresWithKeepTtl() {
        String token = "d".repeat(43);
        String original = "{\"providerId\":\"123\",\"email\":\"a@example.com\","
                + "\"name\":\"홍길동\"}";
        KakaoRegistrationStore.Claim claim = new KakaoRegistrationStore.Claim(
                "kakao:registration:" + sha256(token),
                "CLAIMED|claim-id|" + original,
                original,
                new KakaoRegistrationSession("123", "a@example.com", "홍길동"));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(1L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(1L);

        store.complete(claim);
        store.restore(claim);

        ArgumentCaptor<RedisScript<Long>> scripts = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(scripts.capture(), anyList(), eq(claim.getClaimedValue()));
        verify(redisTemplate).execute(scripts.capture(), anyList(),
                eq(claim.getClaimedValue()), eq(original));
        assertFalse(claim.getKey().contains(token));
        assertTrue(scripts.getAllValues().get(0).getScriptAsString().contains("DEL"));
        assertTrue(scripts.getAllValues().get(1).getScriptAsString().contains("KEEPTTL"));
    }

    @Test
    void readRedisFailureIsConvertedToServiceUnavailable() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("secret redis key"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> store.getAvailable("e".repeat(43)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertFalse(exception.getMessage().contains("redis"));
    }

    private KakaoRegistrationSession session() {
        return new KakaoRegistrationSession("123456789", "member@example.com", "홍길동");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
