package com.aewol.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RecurringPaymentJob {

    /**
     * 매일 09:00 — 정기결제 실행
     * next_payment_date가 오늘인 정기결제 자동 실행
     */
    @Scheduled(cron = "0 0 9 * * *")
    public void executeRecurringPayments() {
        log.info("[Batch] 정기결제 실행 시작");
        // TODO: 구현
        log.info("[Batch] 정기결제 실행 완료");
    }
}
