package com.aewol.config;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 배치가 서로를 밀어내지 않는지 확인한다.
 *
 * <p>{@code TaskScheduler} 빈이 없으면 Spring은 스레드 1개짜리 스케줄러를 쓴다. 그러면 배치
 * 하나가 길어질 때 나머지가 그 뒤에 줄을 선다. 홈 인사이트 예열(04:30)은 회원당 LLM을
 * 1~2초씩 호출하므로 회원이 만 명이면 서너 시간이 걸리고, 그동안 09시 정기결제가 밀린다.
 */
class SchedulingConfigTest {

    @Test
    @DisplayName("스케줄러 빈이 여러 스레드를 쓴다")
    void should_provideMultiThreadedScheduler() {
        TaskScheduler scheduler = new SchedulingConfig().taskScheduler(new SimpleMeterRegistry());

        assertInstanceOf(ThreadPoolTaskScheduler.class, scheduler);
        ThreadPoolTaskScheduler pooled = (ThreadPoolTaskScheduler) scheduler;
        assertTrue(pooled.getPoolSize() > 1,
                "스레드가 하나면 배치가 서로를 밀어낸다.");
        // 배치 로그에는 요청 추적 id가 없어 스레드 이름이 유일한 단서다.
        assertTrue(pooled.getThreadNamePrefix().startsWith("batch"));
    }

    /*
     * 풀 크기의 근거는 "등록된 배치 수"다. cron 잡은 자기 자신과 겹치지 않으므로(Spring이
     * 실행이 끝난 뒤 다음 시각을 계산한다) 서로 다른 잡의 개수가 동시 실행의 상한이다.
     *
     * 근거를 주석으로만 남기면 잡이 늘 때 놓친다. 실제 개수를 세어 확인한다.
     */
    @Test
    @DisplayName("풀 크기가 등록된 배치 수보다 작지 않다")
    void should_haveEnoughThreads_forEveryScheduledJob() {
        Map<String, Integer> jobsByClass = scheduledJobs();
        int total = jobsByClass.values().stream().mapToInt(Integer::intValue).sum();

        ThreadPoolTaskScheduler pooled = (ThreadPoolTaskScheduler) new SchedulingConfig()
                .taskScheduler(new SimpleMeterRegistry());

        assertTrue(pooled.getPoolSize() >= total,
                "배치가 " + total + "개인데 스레드는 " + pooled.getPoolSize() + "개다. "
                        + "부족하면 조용히 예전처럼 줄을 서기 시작한다. 발견된 배치: " + jobsByClass);
    }

    /** {@code @Scheduled}가 붙은 메서드를 클래스별로 센다. */
    private Map<String, Integer> scheduledJobs() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));

        Map<String, Integer> found = new LinkedHashMap<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.aewol")) {
            try {
                Class<?> type = Class.forName(definition.getBeanClassName());
                long count = 0;
                for (Method method : type.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Scheduled.class)) {
                        count++;
                    }
                }
                if (count > 0) {
                    found.put(type.getSimpleName(), (int) count);
                }
            } catch (Throwable ignored) {
                // 로딩할 수 없는 클래스는 배치일 리 없다.
            }
        }
        return found;
    }

    /*
     * 스레드가 하나면 긴 배치가 끝날 때까지 다른 배치가 시작조차 못 한다.
     *
     * 시간을 재는 대신 순서를 본다. 긴 배치를 붙잡아 둔 채로 짧은 배치가 끝나는지 확인하면
     * CI가 느려도 결과가 흔들리지 않는다.
     */
    @Test
    @DisplayName("긴 배치가 도는 동안에도 다른 배치가 시작한다")
    void should_notWaitForRunningJob_beforeStartingAnother() throws Exception {
        ThreadPoolTaskScheduler pooled = (ThreadPoolTaskScheduler) new SchedulingConfig()
                .taskScheduler(new SimpleMeterRegistry());
        pooled.initialize();
        try {
            CountDownLatch longJobStarted = new CountDownLatch(1);
            CountDownLatch releaseLongJob = new CountDownLatch(1);
            pooled.schedule(() -> {
                longJobStarted.countDown();
                try {
                    releaseLongJob.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, Instant.now());

            assertTrue(longJobStarted.await(5, TimeUnit.SECONDS), "긴 배치가 시작되지 않았다");

            CountDownLatch shortJobDone = new CountDownLatch(1);
            pooled.schedule(shortJobDone::countDown, Instant.now());

            // 긴 배치를 아직 풀지 않았는데도 짧은 배치가 끝나야 한다.
            assertTrue(shortJobDone.await(5, TimeUnit.SECONDS),
                    "긴 배치가 끝나기를 기다렸다 — 스레드가 부족하다.");

            releaseLongJob.countDown();
        } finally {
            pooled.shutdown();
        }
    }

    @Test
    @DisplayName("스케줄러 풀 상태를 메트릭으로 내보낸다")
    void should_exportExecutorMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolTaskScheduler pooled = (ThreadPoolTaskScheduler) new SchedulingConfig()
                .taskScheduler(registry);
        pooled.afterPropertiesSet();
        try {
            assertTrue(registry.getMeters().stream().anyMatch(meter ->
                            meter.getId().getName().startsWith("executor")
                                    && "batch".equals(meter.getId().getTag("name"))),
                    "executor.queued가 있어야 풀 포화를 운영에서 볼 수 있다. 등록된 미터: "
                            + registry.getMeters().stream()
                            .map(meter -> meter.getId().toString())
                            .toList());
        } finally {
            pooled.shutdown();
        }
    }
}
