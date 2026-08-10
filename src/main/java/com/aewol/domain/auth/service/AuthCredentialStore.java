package com.aewol.domain.auth.service;

import com.aewol.common.util.JwtUtil;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
        redisTemplate.opsForValue().set(
                refreshKey(memberId), refreshToken,
                jwtUtil.getRefreshTokenExpiry(), TimeUnit.MILLISECONDS);
    }

    public boolean rotateRefreshAtomically(
            String memberId,
            String presentedRefreshToken,
            String newRefreshToken) {
        Long result = redisTemplate.execute(
                ROTATE_REFRESH_SCRIPT,
                List.of(refreshKey(memberId)),
                presentedRefreshToken,
                newRefreshToken,
                String.valueOf(jwtUtil.getRefreshTokenExpiry()));
        return Long.valueOf(1L).equals(result);
    }

    public void deleteRefresh(String memberId) {
        redisTemplate.delete(refreshKey(memberId));
    }

    private String refreshKey(String memberId) {
        return REFRESH_KEY_PREFIX + memberId;
    }
}
