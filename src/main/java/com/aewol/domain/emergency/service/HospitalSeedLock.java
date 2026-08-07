package com.aewol.domain.emergency.service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
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
 *
 * <p>TTL 연장만으로는 부족하다 — 연장에 실패해 소유권을 잃었더라도, 그 사실이 실행 중인
 * {@code action}에 전달되지 않으면 이미 다른 인스턴스가 처리 중인 데이터를 그대로 계속
 * 덮어쓸 수 있다(예: 새 인스턴스가 폐업 병원을 삭제한 직후, 소유권을 잃은 이전 실행이 자신이
 * 수집해둔 오래된 데이터로 그 병원을 다시 upsert). 그래서 {@code action}에는 매번 최신
 * 소유권 상태를 조회할 수 있는 {@link BooleanSupplier}를 함께 넘기고, 호출자는 DB에 쓰기
 * 직전마다(가능하면 매 쓰기마다) 이를 확인해 소유권을 잃었으면 즉시 중단해야 한다.
 *
 * <p>다만 이 확인은 "확인 시점과 실제 쓰기 시점 사이의 아주 짧은 창"까지 막아주진 못한다.
 * 그 창까지 완전히 없애려면 각 쓰기에 fencing token(예: DB에 락 세대값 컬럼을 두고 조건부
 * UPDATE)을 적용해야 하는데, 월 1회 배치인 이 기능의 리스크 대비 스키마 변경 비용이 커서
 * 지금은 적용하지 않았다.
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

    /**
     * @param action 락을 쥐고 실행할 작업. 인자로 전달되는 {@link BooleanSupplier}는 현재도
     *               이 실행이 락 소유자인지를 반환하며, DB 쓰기 직전마다 확인해야 한다.
     */
    public <T> T execute(Function<BooleanSupplier, T> action) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new IllegalStateException("병원 데이터 시딩이 이미 실행 중입니다.");
        }

        AtomicBoolean owned = new AtomicBoolean(true);
        ScheduledExecutorService renewer = newRenewalScheduler();
        renewer.scheduleAtFixedRate(
                () -> renew(token, owned),
                RENEWAL_INTERVAL.toMillis(),
                RENEWAL_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
        try {
            return action.apply(owned::get);
        } finally {
            renewer.shutdownNow();
            try {
                redisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(LOCK_KEY), token);
            } catch (RuntimeException e) {
                log.error("병원 데이터 시딩 잠금 해제 실패 - TTL 만료를 기다립니다.", e);
            }
        }
    }

    /**
     * 소유 토큰이 일치할 때만 TTL을 연장한다. 연장 실패(다른 실행이 이미 락을 가져감) 또는
     * Redis 호출 자체의 실패(소유 여부를 확인할 수 없음)는 모두 소유권 상실로 간주해 안전한
     * 쪽으로 처리한다 — {@code action}이 이 상태를 확인하면 남은 DB 쓰기를 건너뛴다.
     */
    private void renew(String token, AtomicBoolean owned) {
        try {
            Long renewed = redisTemplate.execute(
                    RENEW_SCRIPT,
                    Collections.singletonList(LOCK_KEY),
                    token,
                    String.valueOf(LOCK_TTL.toMillis()));
            if (renewed == null || renewed == 0) {
                owned.set(false);
                log.warn("병원 데이터 시딩 잠금 연장 실패 - 소유권을 상실했습니다. 이후 DB 쓰기는 중단됩니다.");
            }
        } catch (RuntimeException e) {
            owned.set(false);
            log.error("병원 데이터 시딩 잠금 연장 중 오류가 발생했습니다 - 소유권 상실로 간주해 이후 DB 쓰기를 중단합니다.", e);
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
