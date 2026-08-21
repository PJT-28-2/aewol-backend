package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class KakaoPhoneVerificationStore {

    static final long OTP_TTL_SECONDS = 300L;
    static final int MAX_VERIFICATION_ATTEMPTS = 5;
    private static final String VERIFICATION_KEY_PREFIX = "kakao:registration:phone:verify:";
    private static final String VERIFIED_PREFIX = "VERIFIED|";
    private static final String INVALID_SESSION_MESSAGE =
            "유효하지 않거나 만료된 카카오 가입 세션입니다.";
    private static final String INVALID_CODE_MESSAGE =
            "인증번호가 만료되었거나 유효하지 않습니다.";
    private static final String SERVICE_UNAVAILABLE_MESSAGE =
            "전화번호 인증 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해주세요.";
    private static final int ISSUE_NONCE_BYTES = 16;

    private static final DefaultRedisScript<Long> ISSUE_CODE_SCRIPT = new DefaultRedisScript<>(
            "local session = redis.call('GET', KEYS[1])\n" +
            "if not session then return -1 end\n" +
            "if string.sub(session, 1, 8) == 'CLAIMED|' then return -2 end\n" +
            "local decoded, registration = pcall(cjson.decode, session)\n" +
            "if not decoded or type(registration) ~= 'table' then return -3 end\n" +
            "local sessionTtl = redis.call('PTTL', KEYS[1])\n" +
            "if sessionTtl <= 0 then return -1 end\n" +
            "local otpTtl = tonumber(ARGV[2])\n" +
            "if sessionTtl < otpTtl then otpTtl = sessionTtl end\n" +
            "registration['verifiedPhone'] = nil\n" +
            "redis.call('SET', KEYS[1], cjson.encode(registration), 'KEEPTTL')\n" +
            "redis.call('SET', KEYS[2], ARGV[1], 'PX', otpTtl)\n" +
            "return otpTtl",
            Long.class);

    private static final DefaultRedisScript<String> VERIFY_CODE_SCRIPT = new DefaultRedisScript<>(
            "local stored = redis.call('GET', KEYS[1])\n" +
            "if not stored then return 'MISSING' end\n" +
            "if string.sub(stored, 1, 9) == 'VERIFIED|' then\n" +
            "  local phone = string.sub(stored, 10)\n" +
            "  if string.match(phone, '^010%d%d%d%d%d%d%d%d$') then return 'OK|' .. phone end\n" +
            "  return 'INVALID'\n" +
            "end\n" +
            "local phone, code, attempts, issueNonce = string.match(stored, '^(010%d%d%d%d%d%d%d%d)|(%d%d%d%d%d%d)|(%d+)|([0-9a-f]+)$')\n" +
            "if not phone or string.len(issueNonce) ~= 32 then return 'INVALID' end\n" +
            "if code ~= ARGV[1] then\n" +
            "  attempts = tonumber(attempts) + 1\n" +
            "  if attempts >= tonumber(ARGV[2]) then\n" +
            "    redis.call('DEL', KEYS[1])\n" +
            "    return 'MISMATCH_DISCARDED'\n" +
            "  else\n" +
            "    redis.call('SET', KEYS[1], phone .. '|' .. code .. '|' .. attempts .. '|' .. issueNonce, 'KEEPTTL')\n" +
            "    return 'MISMATCH_RETAINED'\n" +
            "  end\n" +
            "end\n" +
            "redis.call('SET', KEYS[1], 'VERIFIED|' .. phone, 'KEEPTTL')\n" +
            "return 'OK|' .. phone",
            String.class);

    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end\n" +
                    "return redis.call('DEL', KEYS[1])",
                    Long.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final SecureRandom secureRandom;

    @Autowired
    public KakaoPhoneVerificationStore(RedisTemplate<String, String> redisTemplate) {
        this(redisTemplate, new SecureRandom());
    }

    KakaoPhoneVerificationStore(
            RedisTemplate<String, String> redisTemplate,
            SecureRandom secureRandom) {
        this.redisTemplate = redisTemplate;
        this.secureRandom = secureRandom;
    }

    public IssuedVerification issue(
            String registrationKey,
            String verificationId,
            String normalizedPhone) {
        validateVerificationId(verificationId);
        if (normalizedPhone == null || !normalizedPhone.matches("^010\\d{8}$")) {
            throw new BusinessException("올바른 휴대전화 번호를 입력해주세요.");
        }

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        byte[] nonceBytes = new byte[ISSUE_NONCE_BYTES];
        secureRandom.nextBytes(nonceBytes);
        String issueNonce = HexFormat.of().formatHex(nonceBytes);
        String value = normalizedPhone + "|" + code + "|0|" + issueNonce;
        Long ttlMillis;
        try {
            ttlMillis = redisTemplate.execute(
                    ISSUE_CODE_SCRIPT,
                    List.of(registrationKey, key(verificationId)),
                    value,
                    String.valueOf(OTP_TTL_SECONDS * 1000L));
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
        if (ttlMillis == null) {
            throw serviceUnavailable();
        }
        if (ttlMillis == -2L) {
            throw BusinessException.conflict("카카오 가입 요청이 이미 처리 중입니다.");
        }
        if (ttlMillis == -3L) {
            throw serviceUnavailable();
        }
        if (ttlMillis <= 0L) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, INVALID_SESSION_MESSAGE);
        }
        long expiresInSeconds = Math.max(1L, (ttlMillis + 999L) / 1000L);
        return new IssuedVerification(verificationId, code, value, expiresInSeconds);
    }

    public String verify(String verificationId, String verificationCode) {
        validateVerificationId(verificationId);
        String result;
        try {
            result = redisTemplate.execute(
                    VERIFY_CODE_SCRIPT,
                    List.of(key(verificationId)),
                    verificationCode,
                    String.valueOf(MAX_VERIFICATION_ATTEMPTS));
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
        if (result == null) {
            throw serviceUnavailable();
        }
        if (!result.startsWith("OK|")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, INVALID_CODE_MESSAGE);
        }
        String phone = result.substring(3);
        if (!phone.matches("^010\\d{8}$")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, INVALID_CODE_MESSAGE);
        }
        return phone;
    }

    public void discard(IssuedVerification issuedVerification) {
        compareAndDelete(
                issuedVerification.getVerificationId(),
                issuedVerification.getStoredValue());
    }

    public void consumeVerified(String verificationId, String normalizedPhone) {
        compareAndDelete(verificationId, VERIFIED_PREFIX + normalizedPhone);
    }

    private void compareAndDelete(String verificationId, String expectedValue) {
        try {
            redisTemplate.execute(
                    COMPARE_AND_DELETE_SCRIPT,
                    List.of(key(verificationId)),
                    expectedValue);
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
    }

    private String key(String verificationId) {
        validateVerificationId(verificationId);
        return VERIFICATION_KEY_PREFIX + verificationId;
    }

    private void validateVerificationId(String verificationId) {
        if (verificationId == null || !verificationId.matches("[0-9a-f]{64}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, INVALID_SESSION_MESSAGE);
        }
    }

    private BusinessException serviceUnavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, SERVICE_UNAVAILABLE_MESSAGE);
    }

    @Getter
    public static final class IssuedVerification {
        private final String verificationId;
        private final String code;
        private final String storedValue;
        private final long expiresInSeconds;

        IssuedVerification(
                String verificationId,
                String code,
                String storedValue,
                long expiresInSeconds) {
            this.verificationId = verificationId;
            this.code = code;
            this.storedValue = storedValue;
            this.expiresInSeconds = expiresInSeconds;
        }
    }
}
