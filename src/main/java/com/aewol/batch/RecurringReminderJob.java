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
        scheduledJobLock.runExclusive(LOCK_KEY, LOCK_TTL, () -> runReminders());
    }

    ReminderCounts runReminders() {
        LocalDate reminderDate = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(3);
        List<Map<String, Object>> upcoming = recurringMapper.findUpcomingPayments(reminderDate.toString());
        log.info("[Batch] 정기결제 3일 전 알림 시작 — 대상 {}건 ({})", upcoming.size(), reminderDate);

        int created = 0;
        int disabled = 0;
        int duplicate = 0;
        int error = 0;
        for (Map<String, Object> row : upcoming) {
            try {
                Object memberId = row.get("member_id");
                Object recurringId = row.get("recurring_id");
                if (memberId == null || recurringId == null) continue;
                BigDecimal price = row.get("price") instanceof BigDecimal
                        ? (BigDecimal) row.get("price")
                        : new BigDecimal(String.valueOf(row.get("price")));
                InboxNotifier.Result result = inboxNotifier.notifyQuietly(
                        String.valueOf(memberId),
                        InboxNotifier.Channel.RECURRING,
                        "RECURRING",
                        "정기결제가 3일 뒤예요",
                        InboxNotifier.text(row.get("product_name"), "정기결제")
                                + " " + InboxNotifier.won(price) + "이 예정되어 있어요.",
                        "/payment/recurring",
                        reminderEventKey(recurringId, reminderDate));
                switch (result) {
                    case CREATED -> created++;
                    case DISABLED -> disabled++;
                    case DUPLICATE -> duplicate++;
                    case FAILED -> error++;
                }
            } catch (RuntimeException exception) {
                error++;
                log.error("[Batch] 정기결제 미리 알림 실패 — recurringId={}", row.get("recurring_id"), exception);
            }
        }
        log.info("[Batch] 정기결제 3일 전 알림 완료 — 생성 {}건 / 설정꺼짐 {}건 / 중복 {}건 / 오류 {}건",
                created, disabled, duplicate, error);
        return new ReminderCounts(created, disabled, duplicate, error);
    }

    record ReminderCounts(int created, int disabled, int duplicate, int error) {}

    static String reminderEventKey(Object recurringId, LocalDate reminderDate) {
        return "recurring:" + recurringId + ":" + reminderDate + ":RECURRING";
    }
}
