package com.aewol.domain.auth.service;

import com.aewol.common.util.JwtUtil;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class AuthCredentialStore {

    private static final String AUTH_EPOCH_KEY_PREFIX = "auth:epoch:";
    private static final String REFRESH_KEY_PREFIX = "refresh:";

    private static final DefaultRedisScript<String> GET_OR_CREATE_EPOCH_SCRIPT = new DefaultRedisScript<>(
            "local currentEpoch = redis.call('GET', KEYS[1])\n" +
            "if currentEpoch then return currentEpoch end\n" +
            "redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])\n" +
            "return ARGV[1]",
            String.class);

    private static final DefaultRedisScript<Long> ADVANCE_EPOCH_SCRIPT = new DefaultRedisScript<>(
            "redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])\n" +
            "redis.call('DEL', KEYS[2])\n" +
            "return 1",
            Long.class);

    private static final DefaultRedisScript<Long> STORE_IF_EPOCH_SCRIPT = new DefaultRedisScript<>(
            "local currentEpoch = redis.call('GET', KEYS[1])\n" +
            "if not currentEpoch or currentEpoch ~= ARGV[1] then return 0 end\n" +
            "redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])\n" +
            "redis.call('PEXPIRE', KEYS[1], ARGV[4])\n" +
            "return 1",
            Long.class);

    private static final DefaultRedisScript<Long> ROTATE_IF_EPOCH_AND_TOKEN_SCRIPT = new DefaultRedisScript<>(
            "local currentEpoch = redis.call('GET', KEYS[1])\n" +
            "if not currentEpoch or currentEpoch ~= ARGV[1] then return 0 end\n" +
            "local storedToken = redis.call('GET', KEYS[2])\n" +
            "if storedToken ~= ARGV[2] then return 0 end\n" +
            "redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[4])\n" +
            "redis.call('PEXPIRE', KEYS[1], ARGV[5])\n" +
            "return 1",
            Long.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final JwtUtil jwtUtil;

    public AuthCredentialStore(RedisTemplate<String, String> redisTemplate, JwtUtil jwtUtil) {
        this.redisTemplate = redisTemplate;
        this.jwtUtil = jwtUtil;
    }

    public String getEpoch(String memberId) {
        return redisTemplate.opsForValue().get(epochKey(memberId));
    }

    public String getOrCreateEpochForLogin(String memberId) {
        String epoch = redisTemplate.execute(
                GET_OR_CREATE_EPOCH_SCRIPT,
                List.of(epochKey(memberId)),
                UUID.randomUUID().toString(),
                String.valueOf(authEpochExpiry()));
        if (epoch == null) {
            throw new IllegalStateException("인증 세대를 생성하거나 조회하지 못했습니다.");
        }
        return epoch;
    }

    public void advanceEpochAndDeleteRefresh(String memberId) {
        Long result = redisTemplate.execute(
                ADVANCE_EPOCH_SCRIPT,
                List.of(epochKey(memberId), refreshKey(memberId)),
                UUID.randomUUID().toString(),
                String.valueOf(authEpochExpiry()));
        if (!Long.valueOf(1L).equals(result)) {
            throw new IllegalStateException("인증 credential 세대 교체에 실패했습니다.");
        }
    }

    public boolean storeRefreshIfEpochUnchanged(
            String memberId, String expectedEpoch, String refreshToken) {
        Long result = redisTemplate.execute(
                STORE_IF_EPOCH_SCRIPT,
                List.of(epochKey(memberId), refreshKey(memberId)),
                requireEpoch(expectedEpoch),
                refreshToken,
                String.valueOf(jwtUtil.getRefreshTokenExpiry()),
                String.valueOf(authEpochExpiry()));
        return Long.valueOf(1L).equals(result);
    }

    public boolean rotateRefreshAtomically(
            String memberId,
            String expectedEpoch,
            String presentedRefreshToken,
            String newRefreshToken) {
        Long result = redisTemplate.execute(
                ROTATE_IF_EPOCH_AND_TOKEN_SCRIPT,
                List.of(epochKey(memberId), refreshKey(memberId)),
                requireEpoch(expectedEpoch),
                presentedRefreshToken,
                newRefreshToken,
                String.valueOf(jwtUtil.getRefreshTokenExpiry()),
                String.valueOf(authEpochExpiry()));
        return Long.valueOf(1L).equals(result);
    }

    private long authEpochExpiry() {
        return Math.max(jwtUtil.getAccessTokenExpiry(), jwtUtil.getRefreshTokenExpiry());
    }

    private String requireEpoch(String epoch) {
        if (epoch == null || epoch.isBlank()) {
            throw new IllegalArgumentException("인증 세대가 필요합니다.");
        }
        return epoch;
    }

    private String epochKey(String memberId) {
        return AUTH_EPOCH_KEY_PREFIX + memberId;
    }

    private String refreshKey(String memberId) {
        return REFRESH_KEY_PREFIX + memberId;
    }
}
