package com.aewol.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DashboardAggregationJob {

    /**
     * 매일 자정 — 지출 집계 배치
     * 전날 거래 데이터를 카테고리/반려동물별로 집계
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void aggregateDailyDashboard() {
        log.info("[Batch] 일일 지출 집계 시작");
        // TODO: 구현
        log.info("[Batch] 일일 지출 집계 완료");
    }
}
