package com.aewol.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 배치가 서로를 밀어내지 않는지 확인한다.
 *
 * <p>{@code TaskScheduler} 빈이 없으면 Spring은 스레드 1개짜리 스케줄러를 쓴다. 지금 배치가
 * 7개라 하나가 길어지면 나머지가 그 뒤에 줄을 선다. 홈 인사이트 예열(04:30)은 회원당 LLM을
 * 1~2초씩 호출하므로 회원이 만 명이면 서너 시간이 걸리고, 그동안 09시 정기결제가 밀린다.
 */
class SchedulingConfigTest {

    @Test
    @DisplayName("스케줄러 빈이 여러 스레드를 쓴다")
    void should_provideMultiThreadedScheduler() {
        TaskScheduler scheduler = new SchedulingConfig().taskScheduler();

        assertInstanceOf(ThreadPoolTaskScheduler.class, scheduler);
        ThreadPoolTaskScheduler pooled = (ThreadPoolTaskScheduler) scheduler;
        assertTrue(pooled.getPoolSize() > 1,
                "스레드가 하나면 배치 7개가 서로를 밀어낸다.");
        // 배포로 컨테이너가 내려갈 때 정기결제가 중간에 끊기면 안 된다.
        assertTrue(pooled.getThreadNamePrefix().startsWith("batch"),
                "배치 로그에는 요청 추적 id가 없어 스레드 이름이 유일한 단서다.");
    }

    /*
     * 실제로 밀리는지 본다. 오래 걸리는 잡을 먼저 띄우고, 곧바로 다른 잡을 예약해 시작까지
     * 걸린 시간을 잰다. 스레드가 하나면 앞 잡이 끝날 때까지 시작조차 못 한다.
     */
    @Test
    @DisplayName("긴 배치가 도는 동안에도 다른 배치가 제때 시작한다")
    void should_notDelayOtherJobs_whileLongJobRuns() throws Exception {
        ThreadPoolTaskScheduler pooled = (ThreadPoolTaskScheduler) new SchedulingConfig().taskScheduler();
        pooled.initialize();
        try {
            long blockMs = 1_000;
            CountDownLatch longJobStarted = new CountDownLatch(1);
            pooled.schedule(() -> {
                longJobStarted.countDown();
                try {
                    Thread.sleep(blockMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, Instant.now());

            assertTrue(longJobStarted.await(2, TimeUnit.SECONDS), "긴 잡이 시작되지 않았다");

            long scheduledAt = System.currentTimeMillis();
            CountDownLatch shortJobStarted = new CountDownLatch(1);
            pooled.schedule(shortJobStarted::countDown, Instant.now());

            assertTrue(shortJobStarted.await(2, TimeUnit.SECONDS), "짧은 잡이 시작되지 않았다");
            long waited = System.currentTimeMillis() - scheduledAt;

            assertTrue(waited < blockMs / 2,
                    "앞 잡이 끝나기를 기다렸다. 대기 " + waited + "ms — 스레드가 부족하다.");
        } finally {
            pooled.shutdown();
        }
    }
}
