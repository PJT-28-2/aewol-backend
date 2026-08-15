package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.auth.dto.KakaoRegistrationSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class KakaoRegistrationStoreTest {

    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KakaoRegistrationStore store;

    @BeforeEach
    void setUp() {
        store = new KakaoRegistrationStore(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
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
        assertEquals("kakao:registration:" + registrationToken, keyCaptor.getValue());

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

    private KakaoRegistrationSession session() {
        return new KakaoRegistrationSession("123456789", "member@example.com", "홍길동");
    }
}
