package com.aewol.batch;

import com.aewol.domain.recurring.mapper.RecurringMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecurringPaymentJob {

    private final RecurringMapper recurringMapper;
    private final RecurringPaymentExecutor executor;

    /**
     * 매일 09:00 — next_payment_date가 오늘인 정기결제 자동 실행.
     * 각 건은 독립 트랜잭션(RecurringPaymentExecutor)으로 처리해 한 건의 실패가 나머지를 막지 않게 한다.
     */
    @Scheduled(cron = "0 0 9 * * *")
    public void executeRecurringPayments() {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> duePayments = recurringMapper.findDuePayments(today.toString());
        log.info("[Batch] 정기결제 실행 시작 — 대상 {}건 ({})", duePayments.size(), today);

        int success = 0, insufficient = 0, error = 0;
        for (Map<String, Object> due : duePayments) {
            try {
                if (executor.execute(due)) {
                    success++;
                } else {
                    insufficient++;
                    log.warn("[Batch] 잔액 부족으로 스킵 — recurringId={}", due.get("recurring_id"));
                }
            } catch (Exception e) {
                error++;
                log.error("[Batch] 정기결제 처리 실패 — recurringId={}", due.get("recurring_id"), e);
            }
        }

        log.info("[Batch] 정기결제 실행 완료 — 성공 {}건 / 잔액부족 {}건 / 오류 {}건",
                success, insufficient, error);
    }
}
