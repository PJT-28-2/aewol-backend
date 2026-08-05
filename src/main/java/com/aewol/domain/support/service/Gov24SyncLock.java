package com.aewol.domain.support.service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 정부24 수동·배치 동기화가 여러 인스턴스에서 겹치지 않도록 하는 공통 잠금. */
@Component
@RequiredArgsConstructor
@Slf4j
public class Gov24SyncLock {

    private static final String LOCK_KEY = "lock:gov24:sync";
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final RedisTemplate<String, String> redisTemplate;

    public <T> T execute(Supplier<T> action) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new IllegalStateException("정부24 동기화가 이미 실행 중입니다.");
        }

        try {
            return action.get();
        } finally {
            try {
                redisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(LOCK_KEY), token);
            } catch (RuntimeException e) {
                log.error("정부24 동기화 잠금 해제 실패 - TTL 만료를 기다립니다.", e);
            }
        }
    }
}
