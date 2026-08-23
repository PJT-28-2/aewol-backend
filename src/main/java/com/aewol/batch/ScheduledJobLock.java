package com.aewol.batch;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 스케줄 배치가 여러 인스턴스에서 동시에 돌지 않게 하는 Redis 잠금.
 *
 * <p>정부24·병원 시딩은 서비스 레이어에 전용 락이 있다. 정기결제·잔돈 적립·공동구매
 * 환불·홈 인사이트 예열은 잡 메서드가 바로 본 작업을 실행해서, 인스턴스가 둘이면
 * 같은 시각에 두 번 돈다. 정기결제는 이중 출금이 될 수 있다.
 *
 * <p>락을 못 잡으면 예외를 던지지 않고 건너뛴다. 스케줄러를 예외로 멈추기보다
 * 다른 인스턴스가 이미 처리 중이라는 로그만 남긴다.
 *
 * <p>Redis가 죽어 락을 확인조차 못 하면 <b>실행하지 않는다.</b> 인증 캐시와 정반대의
 * 선택이다. 거기서는 못 읽으면 DB를 보면 그만이지만, 여기서는 "다른 인스턴스가 지금
 * 돌고 있는지"를 대신 알 방법이 없다. 모르는 채로 실행하면 정기결제가 이중 출금될 수
 * 있으므로, 한 주기를 거르는 쪽을 택한다. 놓친 건은 다음 주기가 다시 집어간다.
 *
 * <p>그래서 건너뛴 이유를 로그에서 구별할 수 있어야 한다. 다른 인스턴스가 잡고 있어서
 * 건너뛴 것과 Redis가 죽어서 건너뛴 것은 대응이 전혀 다르다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledJobLock {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * @return 이 인스턴스가 락을 잡고 실행했으면 true, 이미 다른 실행이 있으면 false
     */
    public boolean runExclusive(String lockKey, Duration ttl, Runnable action) {
        String token = UUID.randomUUID().toString();

        Boolean acquired;
        try {
            acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, token, ttl);
        } catch (RuntimeException e) {
            // 중복 실행을 막을 수 없는 상태다. 실행하지 않고 다음 주기를 기다린다.
            log.error("[Batch] Redis 장애로 잠금을 확인하지 못해 이번 주기를 건너뜁니다. lock={}", lockKey, e);
            return false;
        }

        if (!Boolean.TRUE.equals(acquired)) {
            log.info("[Batch] 다른 인스턴스가 이미 실행 중이라 건너뜁니다. lock={}", lockKey);
            return false;
        }

        try {
            action.run();
            return true;
        } finally {
            try {
                redisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(lockKey), token);
            } catch (RuntimeException e) {
                log.error("[Batch] 잠금 해제 실패 - TTL 만료를 기다립니다. lock={}", lockKey, e);
            }
        }
    }
}
