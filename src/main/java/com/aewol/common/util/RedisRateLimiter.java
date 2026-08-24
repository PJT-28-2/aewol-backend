package com.aewol.common.util;

import com.aewol.common.exception.BusinessException;
import java.util.List;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Redis로 "N초 동안 M회"류 요청 횟수 제한을 구현할 때 쓰는 공용 유틸.
 *
 * INCR 성공 후 EXPIRE를 별도로 호출하면, 그 사이에 인스턴스가 죽거나 Redis 호출이
 * 실패했을 때 TTL 없는 카운터 키가 영구히 남을 수 있다(CodeRabbit 지적, 2026-08-07).
 * 이 두 연산을 Lua 스크립트 하나로 묶으면 Redis가 스크립트 전체를 원자적으로 실행하므로
 * 이런 반쪽 상태가 생기지 않는다.
 */
@Slf4j
@Component
public class RedisRateLimiter {

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<Long> incrementAndExpireScript;
    private final RedisScript<Long> decrementIfPresentScript;

    public RedisRateLimiter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        // KEYS[1] = 카운터 키, ARGV[1] = TTL(초). count가 1(=이번 호출로 키가 새로
        // 만들어짐)일 때만 TTL을 건다 — 이미 TTL이 걸려있는 기존 카운터의 만료
        // 시점을 매번 요청마다 뒤로 미루지 않기 위함(고정 윈도우 방식 유지).
        String script =
                "local count = redis.call('INCR', KEYS[1]) " +
                "if count == 1 then " +
                "  redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
                "end " +
                "return count";
        this.incrementAndExpireScript = new DefaultRedisScript<>(script, Long.class);

        // 키가 있을 때만 줄인다. 없는 키에 DECR을 걸면 0에서 음수로 내려가 다음 윈도우
        // 몫이 늘어난다.
        this.decrementIfPresentScript = new DefaultRedisScript<>(
                "if redis.call('EXISTS', KEYS[1]) == 1 then " +
                "  return redis.call('DECR', KEYS[1]) " +
                "end " +
                "return 0", Long.class);
    }

    /**
     * key의 카운터를 원자적으로 1 증가시키고, 최초 증가(count == 1)일 때만 ttlSeconds로
     * 만료 시간을 건다. 증가 후 카운터 값을 반환한다.
     *
     * Lua 스크립트의 INCR이 정상 실행되면 결과는 항상 1 이상이라 null이 나올 수 없다.
     * null이면 스크립트 실행 자체를 신뢰할 수 없는 상황(연결 문제 등)이라는 뜻인데,
     * 이걸 0으로 취급해서 그냥 통과시키면 호출부(CodefClient, AccountServiceImpl)가
     * 전부 "아직 한도 미만"으로 판단해버려 요청 제한이 무력화된다(CodeRabbit 지적,
     * 2026-08-07). 그래서 결과를 신뢰할 수 없을 땐 통과시키지 않고 예외로 요청을 거부한다.
     */
    public long incrementWithExpiry(String key, long ttlSeconds) {
        Long count;
        try {
            count = redisTemplate.execute(
                    incrementAndExpireScript, List.of(key), String.valueOf(ttlSeconds));
        } catch (RuntimeException e) {
            throw serviceUnavailable();
        }
        if (count == null) {
            throw serviceUnavailable();
        }
        return count;
    }

    private BusinessException serviceUnavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                "요청 제한 확인에 실패했어요. 잠시 후 다시 시도해주세요");
    }

    /**
     * 차감했던 횟수를 되돌린다.
     *
     * <p>차감한 뒤에 정작 작업을 시작하지 못한 경우에 쓴다. 그대로 두면 사용자는 아무것도
     * 받지 못하고 오늘 몫만 잃는다.
     *
     * <p>키가 없으면 아무 일도 하지 않는다 — 자정이 지나 카운터가 만료된 뒤에 되돌리면
     * 0에서 음수로 내려가 다음 날 몫이 늘어나기 때문이다. TTL도 건드리지 않아 고정 윈도우가
     * 유지된다.
     *
     * <p>실패해도 예외를 던지지 않는다. 되돌리지 못하면 사용자가 오늘 한 번을 손해 보지만,
     * 그것 때문에 원래 알려야 할 실패 이유를 가리면 안 된다.
     */
    public void rollback(String key) {
        try {
            redisTemplate.execute(decrementIfPresentScript, List.of(key));
        } catch (RuntimeException e) {
            log.warn("[RATE_LIMIT_ROLLBACK_FAILED] 요청 횟수를 되돌리지 못했습니다. key={}", key, e);
        }
    }
}
