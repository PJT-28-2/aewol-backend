package com.aewol.batch;

import com.aewol.domain.support.service.Gov24SyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class Gov24SyncJob {

    private final Gov24SyncService gov24SyncService;

    /**
     * 매일 04:00 — 정부24 반려동물 공공지원사업 동기화.
     *
     * <p>원본 데이터가 자주 바뀌지 않아 하루 1회로 충분하고,
     * 트래픽이 적은 시간대에 외부 API를 호출한다.
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void syncPetSupportPrograms() {
        log.info("[Batch] 정부24 공공지원사업 동기화 시작");
        try {
            int curated = gov24SyncService.syncPetSupportPrograms();
            log.info("[Batch] 정부24 공공지원사업 동기화 완료: {}건", curated);
        } catch (Exception e) {
            // 외부 API 장애가 애플리케이션 스케줄러를 멈추지 않도록 삼킨다
            log.error("[Batch] 정부24 동기화 실패: {}", e.getMessage(), e);
        }
    }
}
