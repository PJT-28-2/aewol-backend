package com.aewol.domain.auth.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.JwtUtil;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AuthCredentialStore {

    private static final String REFRESH_KEY_PREFIX = "refresh:";

    private static final DefaultRedisScript<Long> ROTATE_REFRESH_SCRIPT = new DefaultRedisScript<>(
            "local storedToken = redis.call('GET', KEYS[1])\n" +
            "if storedToken ~= ARGV[1] then return 0 end\n" +
            "redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])\n" +
            "return 1",
            Long.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final JwtUtil jwtUtil;

    public AuthCredentialStore(RedisTemplate<String, String> redisTemplate, JwtUtil jwtUtil) {
        this.redisTemplate = redisTemplate;
        this.jwtUtil = jwtUtil;
    }

    public void storeRefresh(String memberId, String refreshToken) {
        try {
            redisTemplate.opsForValue().set(
                    refreshKey(memberId), refreshToken,
                    jwtUtil.getRefreshTokenExpiry(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
    }

    public boolean rotateRefreshAtomically(
            String memberId,
            String presentedRefreshToken,
            String newRefreshToken) {
        Long result;
        try {
            result = redisTemplate.execute(
                    ROTATE_REFRESH_SCRIPT,
                    List.of(refreshKey(memberId)),
                    presentedRefreshToken,
                    newRefreshToken,
                    String.valueOf(jwtUtil.getRefreshTokenExpiry()));
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
        if (result == null) {
            throw serviceUnavailable();
        }
        return result == 1L;
    }

    public void deleteRefresh(String memberId) {
        Boolean deleted;
        try {
            deleted = redisTemplate.delete(refreshKey(memberId));
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
        if (deleted == null) {
            throw serviceUnavailable();
        }
    }

    private String refreshKey(String memberId) {
        return REFRESH_KEY_PREFIX + memberId;
    }

    private BusinessException serviceUnavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                "인증 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요.");
    }
}
