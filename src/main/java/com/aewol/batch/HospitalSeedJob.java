package com.aewol.batch;

import com.aewol.domain.emergency.service.HospitalSeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class HospitalSeedJob {

    private final HospitalSeedService hospitalSeedService;

    /**
     * 매월 1일 03:00 — 공공데이터 동물병원 현황 동기화.
     *
     * <p>원본 데이터(지자체 인허가 정보)가 자주 바뀌지 않아 월 1회로 충분하고,
     * 트래픽이 적은 시간대에 외부 API를 호출한다.
     */
    @Scheduled(cron = "0 0 3 1 * ?", zone = "Asia/Seoul")
    public void syncHospitals() {
        log.info("[Batch] 동물병원 데이터 시딩 시작");
        try {
            int upserted = hospitalSeedService.syncHospitals();
            log.info("[Batch] 동물병원 데이터 시딩 완료: {}건", upserted);
        } catch (Exception e) {
            // 외부 API 장애가 애플리케이션 스케줄러를 멈추지 않도록 삼킨다
            log.error("[Batch] 동물병원 데이터 시딩 실패: {}", e.getMessage(), e);
        }
    }
}
