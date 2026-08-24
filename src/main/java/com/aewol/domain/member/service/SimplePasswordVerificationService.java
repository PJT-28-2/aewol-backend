package com.aewol.domain.member.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.member.mapper.MemberMapper;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 송금·충전 승인에 공통으로 사용하는 간편 비밀번호 검증 로직.
 * 화면의 사전 검증 결과만 신뢰하지 말고, 실제 금융 요청 처리 직전에도 이 서비스를 다시 호출해야 한다.
 */
@Service
@RequiredArgsConstructor
public class SimplePasswordVerificationService {
    private static final int MAX_FAILURES = 5;
    private static final long LOCK_SECONDS = 60;
    private static final String FAILURE_KEY_PREFIX = "simple-password:failures:";
    private static final String LOCK_KEY_PREFIX = "simple-password:lock:";

    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;
    private final RedisRateLimiter redisRateLimiter;
    private final RedisTemplate<String, String> redisTemplate;

    public boolean verify(String memberId, String rawPassword) {
        String lockKey = LOCK_KEY_PREFIX + memberId;
        if (Boolean.TRUE.equals(hasKey(lockKey))) {
            throw lockedException();
        }

        Map<String, Object> member = memberMapper.findById(memberId);
        if (member == null) {
            throw BusinessException.notFound("회원을 찾을 수 없습니다.");
        }
        String encodedPassword = (String) member.get("simple_password");
        if (encodedPassword == null || encodedPassword.isBlank()) {
            throw BusinessException.conflict("간편 비밀번호를 먼저 설정해주세요.");
        }

        String failureKey = FAILURE_KEY_PREFIX + memberId;
        if (passwordEncoder.matches(rawPassword, encodedPassword)) {
            delete(failureKey);
            return true;
        }

        long failures = incrementFailures(failureKey);
        if (failures >= MAX_FAILURES) {
            setLock(lockKey);
            delete(failureKey);
            throw lockedException();
        }
        return false;
    }

    private Boolean hasKey(String key) {
        Boolean present;
        try {
            present = redisTemplate.hasKey(key);
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
        if (present == null) {
            throw serviceUnavailable();
        }
        return present;
    }

    private long incrementFailures(String key) {
        try {
            return redisRateLimiter.incrementWithExpiry(key, LOCK_SECONDS);
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
    }

    private void setLock(String key) {
        try {
            redisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(LOCK_SECONDS));
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
    }

    private void delete(String key) {
        Boolean deleted;
        try {
            deleted = redisTemplate.delete(key);
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
        if (deleted == null) {
            throw serviceUnavailable();
        }
    }

    private BusinessException serviceUnavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                "간편 비밀번호 인증 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요.");
    }

    private BusinessException lockedException() {
        return new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                "간편 비밀번호 입력이 잠겼어요. 60초 후 다시 시도해주세요.");
    }
}
