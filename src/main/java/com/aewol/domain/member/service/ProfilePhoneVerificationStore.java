package com.aewol.domain.member.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.Sha256Util;
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
public class ProfilePhoneVerificationStore {

    static final long OTP_TTL_SECONDS = 300L;
    static final int MAX_VERIFICATION_ATTEMPTS = 5;
    private static final String VERIFICATION_KEY_PREFIX = "profile:phone:verify:";
    private static final String VERIFIED_PREFIX = "VERIFIED|";
    private static final String INVALID_CODE_MESSAGE =
            "인증번호가 만료되었거나 유효하지 않습니다.";
    private static final String SERVICE_UNAVAILABLE_MESSAGE =
            "전화번호 인증 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해주세요.";
    private static final int ISSUE_NONCE_BYTES = 16;

    private static final DefaultRedisScript<Long> ISSUE_CODE_SCRIPT = new DefaultRedisScript<>(
            "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])\n" +
            "return tonumber(ARGV[2])",
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
    public ProfilePhoneVerificationStore(RedisTemplate<String, String> redisTemplate) {
        this(redisTemplate, new SecureRandom());
    }

    ProfilePhoneVerificationStore(
            RedisTemplate<String, String> redisTemplate,
            SecureRandom secureRandom) {
        this.redisTemplate = redisTemplate;
        this.secureRandom = secureRandom;
    }

    public IssuedVerification issue(String memberId, String normalizedPhone) {
        if (normalizedPhone == null || !normalizedPhone.matches("^010\\d{8}$")) {
            throw new BusinessException("올바른 휴대전화 번호를 입력해주세요.");
        }

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        byte[] nonceBytes = new byte[ISSUE_NONCE_BYTES];
        secureRandom.nextBytes(nonceBytes);
        String issueNonce = HexFormat.of().formatHex(nonceBytes);
        String value = normalizedPhone + "|" + code + "|0|" + issueNonce;
        Long ttlSeconds;
        try {
            ttlSeconds = redisTemplate.execute(
                    ISSUE_CODE_SCRIPT,
                    List.of(key(memberId)),
                    value,
                    String.valueOf(OTP_TTL_SECONDS));
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
        if (ttlSeconds == null || ttlSeconds <= 0L) {
            throw serviceUnavailable();
        }
        return new IssuedVerification(memberId, code, value, ttlSeconds);
    }

    public String verify(String memberId, String verificationCode) {
        String result;
        try {
            result = redisTemplate.execute(
                    VERIFY_CODE_SCRIPT,
                    List.of(key(memberId)),
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
        compareAndDelete(issuedVerification.getMemberId(), issuedVerification.getStoredValue());
    }

    public boolean consumeVerified(String memberId, String normalizedPhone) {
        Long deleted = compareAndDelete(memberId, VERIFIED_PREFIX + normalizedPhone);
        return deleted != null && deleted == 1L;
    }

    private Long compareAndDelete(String memberId, String expectedValue) {
        try {
            return redisTemplate.execute(
                    COMPARE_AND_DELETE_SCRIPT,
                    List.of(key(memberId)),
                    expectedValue);
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
    }

    private String key(String memberId) {
        return VERIFICATION_KEY_PREFIX + Sha256Util.lowercaseHex(memberId);
    }

    private BusinessException serviceUnavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, SERVICE_UNAVAILABLE_MESSAGE);
    }

    @Getter
    public static final class IssuedVerification {
        private final String memberId;
        private final String code;
        private final String storedValue;
        private final long expiresInSeconds;

        IssuedVerification(
                String memberId,
                String code,
                String storedValue,
                long expiresInSeconds) {
            this.memberId = memberId;
            this.code = code;
            this.storedValue = storedValue;
            this.expiresInSeconds = expiresInSeconds;
        }
    }
}
