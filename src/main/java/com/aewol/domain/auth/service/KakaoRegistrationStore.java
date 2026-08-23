package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.exception.ErrorCode;
import com.aewol.common.util.Sha256Util;
import com.aewol.domain.auth.dto.KakaoRegistrationSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class KakaoRegistrationStore {

    static final long REGISTRATION_TTL_SECONDS = 900L;
    private static final String REGISTRATION_KEY_PREFIX = "kakao:registration:";
    private static final String CLAIM_PREFIX = "CLAIMED|";
    private static final String INVALID_SESSION_MESSAGE =
            "유효하지 않거나 만료된 카카오 가입 세션입니다.";
    private static final String SESSION_UNAVAILABLE_MESSAGE =
            "카카오 가입을 진행할 수 없습니다. 잠시 후 다시 시도해주세요.";
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_TOKEN_GENERATION_ATTEMPTS = 3;

    private static final DefaultRedisScript<String> UPDATE_VERIFIED_PHONE_SCRIPT =
            new DefaultRedisScript<>(
                    "local stored = redis.call('GET', KEYS[1])\n" +
                    "if not stored then return 'MISSING' end\n" +
                    "if string.sub(stored, 1, 8) == 'CLAIMED|' then return 'CLAIMED' end\n" +
                    "local decoded, session = pcall(cjson.decode, stored)\n" +
                    "if not decoded or type(session) ~= 'table' then return 'INVALID' end\n" +
                    "session['verifiedPhone'] = ARGV[1]\n" +
                    "redis.call('SET', KEYS[1], cjson.encode(session), 'KEEPTTL')\n" +
                    "return 'OK'",
                    String.class);

    private static final DefaultRedisScript<String> CLAIM_SESSION_SCRIPT =
            new DefaultRedisScript<>(
                    "local stored = redis.call('GET', KEYS[1])\n" +
                    "if not stored then return 'MISSING' end\n" +
                    "if string.sub(stored, 1, 8) == 'CLAIMED|' then return 'CLAIMED' end\n" +
                    "redis.call('SET', KEYS[1], ARGV[1] .. stored, 'KEEPTTL')\n" +
                    "return 'OK|' .. stored",
                    String.class);

    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end\n" +
                    "return redis.call('DEL', KEYS[1])",
                    Long.class);

    private static final DefaultRedisScript<Long> COMPARE_AND_RESTORE_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end\n" +
                    "redis.call('SET', KEYS[1], ARGV[2], 'KEEPTTL')\n" +
                    "return 1",
                    Long.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final SecureRandom secureRandom;
    private final ObjectMapper objectMapper;

    public KakaoRegistrationStore(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.secureRandom = new SecureRandom();
        this.objectMapper = new ObjectMapper();
    }

    public String create(KakaoRegistrationSession session) {
        validateSession(session);
        String serializedSession = serialize(session);
        for (int attempt = 0; attempt < MAX_TOKEN_GENERATION_ATTEMPTS; attempt++) {
            String registrationToken = generateToken();
            Boolean stored;
            try {
                stored = redisTemplate.opsForValue().setIfAbsent(
                        redisKey(registrationToken),
                        serializedSession,
                        REGISTRATION_TTL_SECONDS,
                        TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                throw serviceUnavailable();
            }
            if (Boolean.TRUE.equals(stored)) {
                return registrationToken;
            }
        }
        throw new IllegalStateException("카카오 가입 세션 토큰을 발급할 수 없습니다.");
    }

    public KakaoRegistrationSession getAvailable(String registrationToken) {
        String stored;
        try {
            stored = redisTemplate.opsForValue().get(redisKey(registrationToken));
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
        if (!StringUtils.hasText(stored)) {
            throw invalidSession();
        }
        if (stored.startsWith(CLAIM_PREFIX)) {
            throw BusinessException.conflict("카카오 가입 요청이 이미 처리 중입니다.");
        }
        return deserialize(stored);
    }

    public void updateVerifiedPhone(String registrationToken, String normalizedPhone) {
        if (!StringUtils.hasText(normalizedPhone) || !normalizedPhone.matches("^010\\d{8}$")) {
            throw invalidSession();
        }
        String result;
        try {
            result = redisTemplate.execute(
                    UPDATE_VERIFIED_PHONE_SCRIPT,
                    List.of(redisKey(registrationToken)),
                    normalizedPhone);
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
        if ("CLAIMED".equals(result)) {
            throw BusinessException.conflict("카카오 가입 요청이 이미 처리 중입니다.");
        }
        if (!"OK".equals(result)) {
            throw invalidSession();
        }
    }

    public Claim claim(String registrationToken) {
        String claimPrefix = CLAIM_PREFIX + UUID.randomUUID() + "|";
        String result;
        String key = redisKey(registrationToken);
        try {
            result = redisTemplate.execute(CLAIM_SESSION_SCRIPT, List.of(key), claimPrefix);
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
        if ("CLAIMED".equals(result)) {
            throw BusinessException.conflict("카카오 가입 요청이 이미 처리 중입니다.");
        }
        if (result == null || "MISSING".equals(result) || !result.startsWith("OK|")) {
            throw invalidSession();
        }
        String originalValue = result.substring(3);
        KakaoRegistrationSession session = deserialize(originalValue);
        return new Claim(key, claimPrefix + originalValue, originalValue, session);
    }

    public void complete(Claim claim) {
        Long deleted;
        try {
            deleted = redisTemplate.execute(
                    COMPARE_AND_DELETE_SCRIPT,
                    List.of(claim.getKey()),
                    claim.getClaimedValue());
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
        if (!Long.valueOf(1L).equals(deleted)) {
            throw serviceUnavailable();
        }
    }

    public void restore(Claim claim) {
        try {
            redisTemplate.execute(
                    COMPARE_AND_RESTORE_SCRIPT,
                    List.of(claim.getKey()),
                    claim.getClaimedValue(),
                    claim.getOriginalValue());
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
    }

    String redisKey(String registrationToken) {
        if (registrationToken == null || !registrationToken.matches("[A-Za-z0-9_-]{43}")) {
            throw invalidSession();
        }
        return REGISTRATION_KEY_PREFIX + Sha256Util.lowercaseHex(registrationToken);
    }

    private String serialize(KakaoRegistrationSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("카카오 가입 세션을 저장할 수 없습니다.", e);
        }
    }

    private KakaoRegistrationSession deserialize(String stored) {
        try {
            KakaoRegistrationSession session = objectMapper.readValue(
                    stored, KakaoRegistrationSession.class);
            validateSession(session);
            return session;
        } catch (JsonProcessingException e) {
            throw invalidSession();
        }
    }

    private void validateSession(KakaoRegistrationSession session) {
        if (session == null
                || !StringUtils.hasText(session.getProviderId())
                || session.getProviderId().length() > 100
                || !StringUtils.hasText(session.getEmail())
                || session.getEmail().length() > 100
                || !StringUtils.hasText(session.getName())
                || session.getName().length() > 20
                || (session.getVerifiedPhone() != null
                && !session.getVerifiedPhone().matches("^010\\d{8}$"))) {
            throw invalidSession();
        }
    }

    private String generateToken() {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private BusinessException invalidSession() {
        return new BusinessException(
                HttpStatus.BAD_REQUEST,
                INVALID_SESSION_MESSAGE,
                ErrorCode.KAKAO_REGISTRATION_SESSION_INVALID_OR_EXPIRED);
    }

    private BusinessException serviceUnavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, SESSION_UNAVAILABLE_MESSAGE);
    }

    @Getter
    public static final class Claim {
        private final String key;
        private final String claimedValue;
        private final String originalValue;
        private final KakaoRegistrationSession session;

        Claim(
                String key,
                String claimedValue,
                String originalValue,
                KakaoRegistrationSession session) {
            this.key = key;
            this.claimedValue = claimedValue;
            this.originalValue = originalValue;
            this.session = session;
        }
    }
}
