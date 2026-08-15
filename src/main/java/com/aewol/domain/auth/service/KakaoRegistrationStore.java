package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.auth.dto.KakaoRegistrationSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class KakaoRegistrationStore {

    static final long REGISTRATION_TTL_SECONDS = 900L;
    private static final String REGISTRATION_KEY_PREFIX = "kakao:registration:";
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_TOKEN_GENERATION_ATTEMPTS = 3;

    private final RedisTemplate<String, String> redisTemplate;
    private final SecureRandom secureRandom;
    private final ObjectMapper objectMapper;

    public KakaoRegistrationStore(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.secureRandom = new SecureRandom();
        this.objectMapper = new ObjectMapper();
    }

    public String create(KakaoRegistrationSession session) {
        String serializedSession = serialize(session);
        for (int attempt = 0; attempt < MAX_TOKEN_GENERATION_ATTEMPTS; attempt++) {
            String registrationToken = generateToken();
            Boolean stored;
            try {
                stored = redisTemplate.opsForValue().setIfAbsent(
                        key(registrationToken),
                        serializedSession,
                        REGISTRATION_TTL_SECONDS,
                        TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                        "카카오 가입을 진행할 수 없습니다. 잠시 후 다시 시도해주세요.");
            }
            if (Boolean.TRUE.equals(stored)) {
                return registrationToken;
            }
        }
        throw new IllegalStateException("카카오 가입 세션 토큰을 발급할 수 없습니다.");
    }

    private String serialize(KakaoRegistrationSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("카카오 가입 세션을 저장할 수 없습니다.", e);
        }
    }

    private String generateToken() {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String key(String registrationToken) {
        return REGISTRATION_KEY_PREFIX + registrationToken;
    }
}
