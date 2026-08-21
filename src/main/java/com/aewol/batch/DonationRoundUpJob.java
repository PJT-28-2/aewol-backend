package com.aewol.batch;

import com.aewol.domain.donation.service.DonationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class DonationRoundUpJob {

    static final String ROUND_UP_LOCK_KEY = "lock:batch:donation-roundup";
    static final String AUTO_DONATE_LOCK_KEY = "lock:batch:donation-auto-donate";
    static final Duration LOCK_TTL = Duration.ofMinutes(30);

    private final DonationService donationService;
    private final ScheduledJobLock scheduledJobLock;

    /**
     * 매일 23:00 — 결제액을 적립 단위로 올렸을 때 생기는 차액을 짜투리 저금통에 적립
     */
    @Scheduled(cron = "0 0 23 * * *", zone = "Asia/Seoul")
    public void roundUpDonations() {
        scheduledJobLock.runExclusive(ROUND_UP_LOCK_KEY, LOCK_TTL, () -> {
            log.info("[Batch] 잔돈 적립 시작");
            int completedCount = donationService.processDailyRoundUps();
            log.info("[Batch] 잔돈 적립 완료: {}건", completedCount);
        });
    }

    /** 매월 말일 23:10 — 설정한 캠페인으로 저금통 전액 자동 기부 */
    @Scheduled(cron = "0 10 23 L * *", zone = "Asia/Seoul")
    public void autoDonateAtMonthEnd() {
        scheduledJobLock.runExclusive(AUTO_DONATE_LOCK_KEY, LOCK_TTL, () -> {
            String yearMonth = YearMonth.now(ZoneId.of("Asia/Seoul")).toString();
            log.info("[Batch] 월말 자동 기부 시작: {}", yearMonth);
            int completedCount = donationService.processMonthlyAutoDonations(yearMonth);
            log.info("[Batch] 월말 자동 기부 완료: {}건", completedCount);
        });
    }
}
