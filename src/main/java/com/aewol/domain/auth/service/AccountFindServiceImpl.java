package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.EmailMaskingUtil;
import com.aewol.common.util.PhoneNumberUtil;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.auth.dto.AccountFindResultResponse;
import com.aewol.domain.auth.dto.AccountFindSendCodeRequest;
import com.aewol.domain.auth.dto.AccountFindSendCodeResponse;
import com.aewol.domain.auth.dto.AccountFindVerifyRequest;
import com.aewol.domain.member.mapper.MemberMapper;
import com.aewol.external.sms.SmsSender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountFindServiceImpl implements AccountFindService {

    static final long OTP_TTL_SECONDS = 300L;
    static final long RATE_LIMIT_WINDOW_SECONDS = 1800L;
    static final long RATE_LIMIT_MAX = 5L;
    static final int MAX_VERIFICATION_ATTEMPTS = 5;
    static final String RATE_LIMIT_PREFIX = "account:find:request-count:";
    static final String ACTIVE_REQUEST_PREFIX = "account:find:active:";
    static final String VERIFICATION_PREFIX = "account:find:verify:";
    private static final String INVALID_CODE_MESSAGE = "인증번호가 만료되었거나 유효하지 않습니다.";
    private static final String SMS_FAILURE_MESSAGE = "인증번호 발송에 실패했습니다. 잠시 후 다시 시도해 주세요.";
    private static final String VERIFICATION_SERVICE_FAILURE_MESSAGE =
            "인증 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final DefaultRedisScript<Long> REPLACE_ACTIVE_REQUEST_SCRIPT = new DefaultRedisScript<>(
            "local oldRequestId = redis.call('GET', KEYS[1])\n" +
            "if oldRequestId then redis.call('DEL', ARGV[3] .. oldRequestId) end\n" +
            "redis.call('SET', KEYS[2], ARGV[1], 'EX', ARGV[2])\n" +
            "redis.call('SET', KEYS[1], ARGV[4], 'EX', ARGV[2])\n" +
            "return 1",
            Long.class);

    private static final DefaultRedisScript<Long> CLEANUP_REQUEST_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end\n" +
            "redis.call('DEL', KEYS[1])\n" +
            "redis.call('DEL', KEYS[2])\n" +
            "return 1",
            Long.class);

    private static final DefaultRedisScript<String> VERIFY_REQUEST_SCRIPT = new DefaultRedisScript<>(
            "local stored = redis.call('GET', KEYS[1])\n" +
            "if not stored then return 'MISSING' end\n" +
            "local subject, memberId, code, attempts = string.match(stored, '^([0-9a-f]+)|(%d+)|(%d%d%d%d%d%d)|(%d+)$')\n" +
            "if not subject or redis.call('GET', KEYS[2]) ~= ARGV[1] then return 'MISSING' end\n" +
            "if code ~= ARGV[2] then\n" +
            "  attempts = tonumber(attempts) + 1\n" +
            "  if attempts >= tonumber(ARGV[3]) then\n" +
            "    redis.call('DEL', KEYS[1])\n" +
            "    redis.call('DEL', KEYS[2])\n" +
            "  else\n" +
            "    redis.call('SET', KEYS[1], subject .. '|' .. memberId .. '|' .. code .. '|' .. attempts, 'KEEPTTL')\n" +
            "  end\n" +
            "  return 'MISMATCH'\n" +
            "end\n" +
            "redis.call('DEL', KEYS[1])\n" +
            "redis.call('DEL', KEYS[2])\n" +
            "return 'OK|' .. memberId",
            String.class);

    private final MemberMapper memberMapper;
    private final RedisTemplate<String, String> redisTemplate;
    private final RedisRateLimiter redisRateLimiter;
    private final SmsSender smsSender;

    @Override
    public AccountFindSendCodeResponse sendVerificationCode(AccountFindSendCodeRequest request) {
        String phone = PhoneNumberUtil.normalize(request.getPhone());
        if (phone == null || !phone.matches("^010\\d{8}$")) {
            throw new BusinessException("올바른 휴대전화 번호를 입력해 주세요.");
        }
        String subject = subjectHash(phone);
        long count;
        try {
            count = redisRateLimiter.incrementWithExpiry(
                    RATE_LIMIT_PREFIX + subject, RATE_LIMIT_WINDOW_SECONDS);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw verificationServiceUnavailable();
        }
        if (count > RATE_LIMIT_MAX) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "계정 찾기 인증번호 요청이 너무 많습니다. 30분 후 다시 시도해 주세요.");
        }

        String requestId = UUID.randomUUID().toString();
        List<Map<String, Object>> candidates = memberMapper.findActiveForAccountFind(
                request.getName().trim(), phone);
        if (candidates.size() != 1) {
            if (candidates.size() > 1) {
                log.error("계정 찾기 회원 조회 결과가 중복되어 발송을 중단했습니다. candidateCount={}", candidates.size());
            }
            return new AccountFindSendCodeResponse(requestId, OTP_TTL_SECONDS);
        }

        Map<String, Object> member = candidates.get(0);
        String memberId = String.valueOf(member.get("member_id"));
        if (!memberId.matches("\\d+")) {
            log.error("계정 찾기 회원 조회 결과의 식별자가 유효하지 않아 발송을 중단했습니다.");
            return new AccountFindSendCodeResponse(requestId, OTP_TTL_SECONDS);
        }

        String code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        String verificationValue = subject + "|" + memberId + "|" + code + "|0";
        String activeKey = ACTIVE_REQUEST_PREFIX + subject;
        String verificationKey = VERIFICATION_PREFIX + requestId;
        try {
            Long replaced = redisTemplate.execute(
                    REPLACE_ACTIVE_REQUEST_SCRIPT,
                    List.of(activeKey, verificationKey),
                    verificationValue,
                    String.valueOf(OTP_TTL_SECONDS),
                    VERIFICATION_PREFIX,
                    requestId);
            if (replaced == null || replaced != 1L) {
                throw verificationServiceUnavailable();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw verificationServiceUnavailable();
        }

        try {
            smsSender.send(phone, "[AeWol] 계정 찾기 인증번호: " + code + " (5분 이내 입력)");
        } catch (RuntimeException e) {
            try {
                redisTemplate.execute(CLEANUP_REQUEST_SCRIPT,
                        List.of(activeKey, verificationKey), requestId);
            } catch (RuntimeException cleanupException) {
                e.addSuppressed(cleanupException);
            }
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, SMS_FAILURE_MESSAGE);
        }
        return new AccountFindSendCodeResponse(requestId, OTP_TTL_SECONDS);
    }

    @Override
    public AccountFindResultResponse verifyCode(AccountFindVerifyRequest request) {
        if (!isUuid(request.getRequestId())) {
            throw new BusinessException(INVALID_CODE_MESSAGE);
        }
        String verificationKey = VERIFICATION_PREFIX + request.getRequestId();
        String stored;
        try {
            stored = redisTemplate.opsForValue().get(verificationKey);
        } catch (RuntimeException e) {
            throw verificationServiceUnavailable();
        }
        String subject = subjectFrom(stored);
        if (subject == null) {
            throw new BusinessException(INVALID_CODE_MESSAGE);
        }

        String result;
        try {
            result = redisTemplate.execute(
                    VERIFY_REQUEST_SCRIPT,
                    List.of(verificationKey, ACTIVE_REQUEST_PREFIX + subject),
                    request.getRequestId(),
                    request.getVerificationCode(),
                    String.valueOf(MAX_VERIFICATION_ATTEMPTS));
        } catch (RuntimeException e) {
            throw verificationServiceUnavailable();
        }
        if (result == null || !result.startsWith("OK|")) {
            throw new BusinessException(INVALID_CODE_MESSAGE);
        }

        String memberId = result.substring(3);
        Map<String, Object> member = memberMapper.findActiveAccountFindResultById(memberId);
        if (member == null) {
            throw new BusinessException(INVALID_CODE_MESSAGE);
        }
        String provider = String.valueOf(member.get("provider"));
        if ("KAKAO".equals(provider)) {
            return new AccountFindResultResponse("KAKAO", null);
        }
        if (!"LOCAL".equals(provider)) {
            throw new BusinessException(INVALID_CODE_MESSAGE);
        }
        return new AccountFindResultResponse("LOCAL", EmailMaskingUtil.mask((String) member.get("email")));
    }

    private String subjectFrom(String stored) {
        if (!StringUtils.hasText(stored)) {
            return null;
        }
        int delimiter = stored.indexOf('|');
        if (delimiter != 64) {
            return null;
        }
        String subject = stored.substring(0, delimiter);
        return subject.matches("[0-9a-f]{64}") ? subject : null;
    }

    private String subjectHash(String phone) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(phone.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private BusinessException verificationServiceUnavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                VERIFICATION_SERVICE_FAILURE_MESSAGE);
    }
}
