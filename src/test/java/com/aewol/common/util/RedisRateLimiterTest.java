package com.aewol.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * INCR와 EXPIRE를 따로 호출하면 그 사이 인스턴스가 죽었을 때 TTL 없는 카운터가 영구히
 * 남을 수 있다(CodeRabbit 지적, 2026-08-07) — 그래서 하나의 Lua 스크립트로 원자 실행하게
 * 바꿨다. Redis 서버 없이도 검증 가능하도록, "스크립트가 실제로 실행되는지"와
 * "반환값이 그대로 전달되는지"만 Mockito로 확인한다(Lua 스크립트 내부 로직 자체의
 * 정확성은 실제 Redis 없이는 검증할 수 없음).
 */
@ExtendWith(MockitoExtension.class)
class RedisRateLimiterTest {

    @Mock
    RedisTemplate<String, String> redisTemplate;

    @Test
    @DisplayName("incrementWithExpiry는 INCR+EXPIRE를 원자 실행하는 Lua 스크립트 하나로 카운터 키를 처리하고 그 결과를 반환한다")
    void should_executeAtomicScript_andReturnCount() {
        when(redisTemplate.execute(
                (RedisScript<Long>) org.mockito.ArgumentMatchers.any(),
                anyList(),
                eq("180")))
                .thenReturn(3L);

        RedisRateLimiter limiter = new RedisRateLimiter(redisTemplate);
        long result = limiter.incrementWithExpiry("some-key", 180);

        assertEquals(3L, result);
        verify(redisTemplate).execute(
                (RedisScript<Long>) org.mockito.ArgumentMatchers.any(),
                eq(List.of("some-key")),
                eq("180"));
    }

    @Test
    @DisplayName("Redis 실행 결과가 null이면 0을 반환한다")
    void should_returnZero_whenRedisReturnsNull() {
        when(redisTemplate.execute(
                (RedisScript<Long>) org.mockito.ArgumentMatchers.any(),
                anyList(),
                eq("60")))
                .thenReturn(null);

        RedisRateLimiter limiter = new RedisRateLimiter(redisTemplate);
        long result = limiter.incrementWithExpiry("another-key", 60);

        assertEquals(0L, result);
    }
}
