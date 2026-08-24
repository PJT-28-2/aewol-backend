package com.aewol.batch;

import com.aewol.domain.donation.mapper.DonationMapper;
import com.aewol.domain.donation.service.DonationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DonationRoundUpJob {

    static final String ROUND_UP_LOCK_KEY = "lock:batch:donation-roundup";
    static final String AUTO_DONATE_LOCK_KEY = "lock:batch:donation-auto-donate";
    static final Duration LOCK_TTL = Duration.ofMinutes(30);

    private final DonationMapper donationMapper;
    private final DonationRoundUpExecutor executor;
    private final DonationService donationService;
    private final ScheduledJobLock scheduledJobLock;

    /**
     * 매일 23:00 — 애월지갑 잔액을 회원이 고른 단위로 깎아 나머지를 저금통으로 옮긴다.
     * 각 회원은 독립 트랜잭션(DonationRoundUpExecutor)으로 처리해 한 명의 실패가 나머지를 막지 않게
     * 하고, 지갑 행에 대한 FOR UPDATE 락도 배치 전체가 아니라 그 회원이 끝날 때까지만
     * 유지되게 한다(GroupPurchaseRefundJob과 동일 패턴).
     */
    @Scheduled(cron = "0 0 23 * * *", zone = "Asia/Seoul")
    public void roundUpDonations() {
        scheduledJobLock.runExclusive(ROUND_UP_LOCK_KEY, LOCK_TTL, this::runRoundUps);
    }

    private void runRoundUps() {
        LocalDate today = LocalDate.now(DonationRoundUpExecutor.SEOUL);
        List<Map<String, Object>> candidates = donationMapper.findSpareTrimCandidates(today);
        log.info("[Batch] 자투리 절삭 시작 — 대상 {}명", candidates.size());

        int success = 0, skipped = 0, error = 0;
        for (Map<String, Object> candidate : candidates) {
            try {
                if (executor.execute(candidate)) {
                    success++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                error++;
                log.error("[Batch] 자투리 절삭 처리 실패 — memberId={}",
                        candidate.get("memberId"), e);
            }
        }

        log.info("[Batch] 자투리 절삭 완료 — 성공 {}건 / 스킵 {}건 / 오류 {}건", success, skipped, error);
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
