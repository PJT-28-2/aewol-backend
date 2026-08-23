package com.aewol.batch;

import com.aewol.domain.recurring.mapper.RecurringMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecurringPaymentJob {

    static final String LOCK_KEY = "lock:batch:recurring-payment";
    static final Duration LOCK_TTL = Duration.ofHours(1);

    private final RecurringMapper recurringMapper;
    private final RecurringPaymentExecutor executor;
    private final ScheduledJobLock scheduledJobLock;

    /**
     * 매일 09:00 — next_payment_date가 오늘인 정기결제 자동 실행.
     * 각 건은 독립 트랜잭션(RecurringPaymentExecutor)으로 처리해 한 건의 실패가 나머지를 막지 않게 한다.
     */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void executeRecurringPayments() {
        scheduledJobLock.runExclusive(LOCK_KEY, LOCK_TTL, this::runDuePayments);
    }

    private void runDuePayments() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        List<Map<String, Object>> duePayments = recurringMapper.findDuePayments(today.toString());
        log.info("[Batch] 정기결제 실행 시작 — 대상 {}건 ({})", duePayments.size(), today);

        int success = 0, skipped = 0, error = 0;
        for (Map<String, Object> due : duePayments) {
            try {
                if (executor.execute(due)) {
                    success++;
                } else {
                    skipped++;
                    log.warn("[Batch] 처리 조건 불충족으로 스킵 — recurringId={}", due.get("recurring_id"));
                }
            } catch (Exception e) {
                error++;
                log.error("[Batch] 정기결제 처리 실패 — recurringId={}", due.get("recurring_id"), e);
            }
        }

        log.info("[Batch] 정기결제 실행 완료 — 성공 {}건 / 스킵 {}건 / 오류 {}건",
                success, skipped, error);
    }
}
