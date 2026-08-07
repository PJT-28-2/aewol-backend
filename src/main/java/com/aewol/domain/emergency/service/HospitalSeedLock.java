package com.aewol.domain.emergency.service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 병원 데이터 시딩 배치가 여러 인스턴스에서 겹치지 않도록 하는 분산 잠금.
 *
 * <p>전국 동물병원 수만 건을 페이지네이션으로 수집한 뒤 한 트랜잭션에 upsert하는 작업이라
 * TTL(30분)보다 오래 걸릴 수 있다. 그동안 다른 인스턴스가 락을 획득해 동시에 upsert하는
 * 것을 막기 위해, 소유 토큰이 일치할 때만 TTL을 갱신하는 백그라운드 스케줄러를 둔다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HospitalSeedLock {

    private static final String LOCK_KEY = "lock:emergency:hospital-seed";
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);
    /** TTL의 1/3 주기로 갱신해 네트워크 지연 한두 번으로는 만료되지 않도록 여유를 둔다. */
    private static final Duration RENEWAL_INTERVAL = LOCK_TTL.dividedBy(3);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private final RedisTemplate<String, String> redisTemplate;

    public <T> T execute(Supplier<T> action) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new IllegalStateException("병원 데이터 시딩이 이미 실행 중입니다.");
        }

        ScheduledExecutorService renewer = newRenewalScheduler();
        renewer.scheduleAtFixedRate(
                () -> renew(token),
                RENEWAL_INTERVAL.toMillis(),
                RENEWAL_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
        try {
            return action.get();
        } finally {
            renewer.shutdownNow();
            try {
                redisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(LOCK_KEY), token);
            } catch (RuntimeException e) {
                log.error("병원 데이터 시딩 잠금 해제 실패 - TTL 만료를 기다립니다.", e);
            }
        }
    }

    /** 소유 토큰이 일치할 때만 TTL을 연장한다 (다른 실행이 이미 락을 가져간 경우 갱신하지 않음). */
    private void renew(String token) {
        try {
            Long renewed = redisTemplate.execute(
                    RENEW_SCRIPT,
                    Collections.singletonList(LOCK_KEY),
                    token,
                    String.valueOf(LOCK_TTL.toMillis()));
            if (renewed == null || renewed == 0) {
                log.warn("병원 데이터 시딩 잠금 연장 실패 - 소유권을 이미 상실했을 수 있습니다.");
            }
        } catch (RuntimeException e) {
            log.error("병원 데이터 시딩 잠금 연장 중 오류가 발생했습니다.", e);
        }
    }

    private ScheduledExecutorService newRenewalScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hospital-seed-lock-renewer");
            thread.setDaemon(true);
            return thread;
        });
    }
}
