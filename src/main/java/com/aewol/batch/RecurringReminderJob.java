package com.aewol.batch;

import com.aewol.domain.notification.service.InboxNotifier;
import com.aewol.domain.recurring.mapper.RecurringMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecurringReminderJob {

    static final String LOCK_KEY = "lock:batch:recurring-reminder";
    static final Duration LOCK_TTL = Duration.ofHours(1);

    private final RecurringMapper recurringMapper;
    private final InboxNotifier inboxNotifier;
    private final ScheduledJobLock scheduledJobLock;

    /** 매일 09:05 — 3일 뒤 결제 예정인 정기결제를 알림함에 남긴다. */
    @Scheduled(cron = "0 5 9 * * *", zone = "Asia/Seoul")
    public void remindUpcomingPayments() {
        scheduledJobLock.runExclusive(LOCK_KEY, LOCK_TTL, this::runReminders);
    }

    private void runReminders() {
        LocalDate reminderDate = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(3);
        List<Map<String, Object>> upcoming = recurringMapper.findUpcomingPayments(reminderDate.toString());
        log.info("[Batch] 정기결제 3일 전 알림 시작 — 대상 {}건 ({})", upcoming.size(), reminderDate);

        int success = 0;
        int error = 0;
        for (Map<String, Object> row : upcoming) {
            try {
                Object memberId = row.get("member_id");
                if (memberId == null) continue;
                BigDecimal price = row.get("price") instanceof BigDecimal
                        ? (BigDecimal) row.get("price")
                        : new BigDecimal(String.valueOf(row.get("price")));
                inboxNotifier.notifyQuietly(
                        String.valueOf(memberId),
                        InboxNotifier.Channel.RECURRING,
                        "RECURRING",
                        "정기결제가 3일 뒤예요",
                        InboxNotifier.text(row.get("product_name"), "정기결제")
                                + " " + InboxNotifier.won(price) + "이 예정되어 있어요.",
                        "/payment/recurring");
                success++;
            } catch (RuntimeException exception) {
                error++;
                log.error("[Batch] 정기결제 미리 알림 실패 — recurringId={}", row.get("recurring_id"), exception);
            }
        }
        log.info("[Batch] 정기결제 3일 전 알림 완료 — 성공 {}건 / 오류 {}건", success, error);
    }
}
