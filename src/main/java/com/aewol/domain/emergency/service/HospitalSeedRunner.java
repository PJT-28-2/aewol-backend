package com.aewol.domain.emergency.service;

import com.aewol.external.animalhospital.AnimalHospitalClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 동물병원 시딩을 요청 스레드와 분리해 백그라운드로 실행한다.
 *
 * <p>전국 동물병원은 2026-08 기준 약 10,591건(약 106페이지)이라 수집·upsert에 수 분이 걸린다.
 * 관리자 API가 완료까지 기다리면 Nginx {@code proxy_read_timeout}(기본 60초)에 걸려 게이트웨이
 * 타임아웃이 나므로, 요청은 즉시 202로 끊고 실제 작업은 여기서 돌린다.
 *
 * <p>전역 {@code @EnableAsync}를 도입하지 않고 이 컴포넌트가 전용 단일 스레드 executor를 직접
 * 소유한다. 시딩은 동시에 두 개가 돌 이유가 없는 작업이라 스레드 풀을 공유할 이점이 없고,
 * 프록시 기반 {@code @Async}의 self-invocation 함정도 피할 수 있다.
 */
@Slf4j
@Component
public class HospitalSeedRunner {

    private final HospitalSeedService hospitalSeedService;
    private final AnimalHospitalClient animalHospitalClient;
    private final ExecutorService executor;

    /**
     * 같은 인스턴스에서의 중복 실행(관리자 더블 클릭 등)을 막는 인프로세스 가드.
     *
     * <p>여러 인스턴스에서 동시에 호출된 경우는 여기서 걸러지지 않는다 — 그쪽은 기존 Redis 분산
     * 락({@link HospitalSeedLock})이 담당하고, 뒤늦게 락 획득에 실패한 실행은 이미 202를 응답한
     * 뒤이므로 로그만 남는다.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    // 생성자가 둘이라 @Autowired로 주입 대상을 명시해야 한다. 없으면 Spring이 후보를 못 정해
    // 기본 생성자를 찾다 NoSuchMethodException으로 컨텍스트 로딩이 실패한다.
    @Autowired
    public HospitalSeedRunner(HospitalSeedService hospitalSeedService,
                              AnimalHospitalClient animalHospitalClient) {
        this(hospitalSeedService, animalHospitalClient, newSeedExecutor());
    }

    /** 테스트에서 동기 executor를 주입하기 위한 생성자. */
    HospitalSeedRunner(HospitalSeedService hospitalSeedService,
                       AnimalHospitalClient animalHospitalClient,
                       ExecutorService executor) {
        this.hospitalSeedService = hospitalSeedService;
        this.animalHospitalClient = animalHospitalClient;
        this.executor = executor;
    }

    /** 시딩 시작 요청의 처리 결과. 호출자가 HTTP 상태로 변환한다. */
    public enum StartResult {
        /** 백그라운드 실행을 시작했다. */
        STARTED,
        /** 이 인스턴스에서 이미 시딩이 돌고 있다. */
        ALREADY_RUNNING,
        /** service-key가 없어 외부 API를 호출할 수 없다. */
        NOT_CONFIGURED
    }

    /**
     * 시딩을 백그라운드로 시작한다. 이 메서드는 작업 완료를 기다리지 않고 즉시 반환한다.
     *
     * <p>service-key 미설정을 먼저 확인하는 이유: {@link HospitalSeedService#syncHospitals()}는
     * 키가 없으면 경고 로그만 남기고 0건으로 조용히 끝나는데, 비동기로 돌리면 그 로그조차 응답과
     * 분리되어 관리자는 "시작했다"는 202만 받고 아무 일도 일어나지 않은 이유를 알 수 없다.
     */
    public StartResult start() {
        if (!animalHospitalClient.isConfigured()) {
            log.warn("[HospitalSeed] animal-hospital service-key 미설정 — 수동 시딩 요청을 거부합니다.");
            return StartResult.NOT_CONFIGURED;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("[HospitalSeed] 수동 시딩이 이미 실행 중이라 요청을 거부합니다.");
            return StartResult.ALREADY_RUNNING;
        }

        try {
            executor.execute(this::runAndRelease);
        } catch (RuntimeException e) {
            // execute() 자체가 실패하면(예: 종료된 executor의 RejectedExecutionException)
            // runAndRelease가 아예 호출되지 않아 플래그가 영구히 true로 남고, 이후 모든 요청이
            // 409가 된다. 반드시 되돌린 뒤에 예외를 올려보낸다.
            running.set(false);
            throw e;
        }
        return StartResult.STARTED;
    }

    /**
     * 백그라운드 스레드의 최상위 실행부. 예외가 executor 밖으로 새어나가면 스택트레이스 없이
     * 조용히 삼켜지므로 여기서 직접 로깅한다. 플래그 해제는 성공/실패와 무관하게 수행한다.
     */
    private void runAndRelease() {
        try {
            log.info("[HospitalSeed] 수동 시딩 시작");
            int upserted = hospitalSeedService.syncHospitals();
            log.info("[HospitalSeed] 수동 시딩 완료: {}건", upserted);
        } catch (Exception e) {
            log.error("[HospitalSeed] 수동 시딩 실패: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static ExecutorService newSeedExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hospital-seed-manual");
            // 시딩이 도는 중에도 JVM 종료를 막지 않는다 — 미완료분은 다음 실행에서 upsert된다.
            thread.setDaemon(true);
            return thread;
        });
    }
}
