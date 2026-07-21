package com.aewol.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DonationRoundUpJob {

    /**
     * 매일 23:00 — 잔돈 올림 적립
     * 하루간의 결제에서 올림 차액을 짜투리 저금통에 적립
     */
    @Scheduled(cron = "0 0 23 * * *")
    public void roundUpDonations() {
        log.info("[Batch] 잔돈 올림 적립 시작");
        // TODO: 구현
        log.info("[Batch] 잔돈 올림 적립 완료");
    }
}
