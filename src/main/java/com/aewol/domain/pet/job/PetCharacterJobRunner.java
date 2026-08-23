package com.aewol.domain.pet.job;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 캐릭터 생성을 요청 스레드와 분리해 백그라운드로 실행한다.
 *
 * <p>생성은 Gemini를 두 번 부르느라 20~25초가 걸린다(실측 전신 10.3초 / 프로필 12.2초).
 * 요청이 끝날 때까지 기다리면 그동안 톰캣 스레드 하나가 묶이고, 앞단 프록시의 읽기
 * 타임아웃(기본 60초)에 걸릴 위험도 있다 — {@code HospitalSeedRunner}가 같은 이유로
 * 이미 202 방식을 쓰고 있다.
 *
 * <p>전역 {@code @EnableAsync}를 도입하지 않고 전용 executor를 소유한다. 프록시 기반
 * {@code @Async}의 self-invocation 함정을 피하고, 이 작업만의 동시 실행 수를 따로 정할 수
 * 있기 때문이다.
 */
@Slf4j
@Component
public class PetCharacterJobRunner {

    /**
     * 동시에 두 건만 만든다.
     *
     * <p>호출마다 실제 비용이 나가는 외부 API라 무제한으로 늘릴 이유가 없다. 이미 1인당
     * 하루 횟수 제한이 있으므로, 여기서는 인스턴스 전체가 한꺼번에 몰리는 것만 막으면 된다.
     */
    private static final int CONCURRENCY = 2;

    /**
     * 대기열 길이.
     *
     * <p>무제한 큐를 쓰면 밀린 요청이 계속 쌓여, 사용자는 202를 받고도 한참 뒤에야 결과를
     * 본다. 그럴 바에는 즉시 "지금은 붐빈다"고 알려주는 편이 낫다.
     */
    private static final int QUEUE_CAPACITY = 20;

    private final ExecutorService executor;

    public PetCharacterJobRunner() {
        this(newExecutor());
    }

    /** 테스트에서 동기 executor를 주입하기 위한 생성자. 다른 패키지의 테스트도 쓴다. */
    public PetCharacterJobRunner(ExecutorService executor) {
        this.executor = executor;
    }

    private static ExecutorService newExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(
                CONCURRENCY, CONCURRENCY,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    // 로그에서 어느 작업이 남긴 줄인지 보이게 한다.
                    Thread t = new Thread(r, "pet-character-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * @return 대기열이 가득 차 받지 못했으면 {@code false}
     */
    public boolean submit(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("[PET_CHARACTER_QUEUE_FULL] 대기열이 가득 차 캐릭터 생성을 받지 못했습니다.");
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        // 진행 중인 생성은 마치게 둔다. 중간에 끊으면 Gemini 호출 비용만 쓰고 결과가 없다.
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
