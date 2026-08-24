package com.aewol.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.domain.notification.service.InboxNotifier;
import com.aewol.domain.recurring.mapper.RecurringMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecurringReminderJobTest {

    @Mock RecurringMapper recurringMapper;
    @Mock InboxNotifier inboxNotifier;
    @Mock ScheduledJobLock scheduledJobLock;
    @InjectMocks RecurringReminderJob job;

    @Test
    void should_skipReminders_whenLockIsHeld() {
        when(scheduledJobLock.runExclusive(any(), any(), any())).thenReturn(false);

        job.remindUpcomingPayments();

        verify(scheduledJobLock).runExclusive(
                eq(RecurringReminderJob.LOCK_KEY), eq(RecurringReminderJob.LOCK_TTL), any());
        verify(recurringMapper, never()).findUpcomingPayments(any());
        verify(inboxNotifier, never()).notifyQuietly(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void should_notifyUpcomingPayments_whenLockIsAcquired() {
        LocalDate reminderDate = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(3);
        when(scheduledJobLock.runExclusive(any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return true;
        });
        when(recurringMapper.findUpcomingPayments(any())).thenReturn(List.of(Map.of(
                "recurring_id", 1L,
                "member_id", "member-1",
                "product_name", "강아지 사료",
                "price", new BigDecimal("32000"))));
        when(inboxNotifier.notifyQuietly(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InboxNotifier.Result.CREATED);

        job.remindUpcomingPayments();

        verify(recurringMapper).findUpcomingPayments(any());
        verify(inboxNotifier).notifyQuietly(
                eq("member-1"),
                eq(InboxNotifier.Channel.RECURRING),
                eq("RECURRING"),
                eq("정기결제가 3일 뒤예요"),
                eq("강아지 사료 32000원이 예정되어 있어요."),
                eq("/payment/recurring"),
                eq(RecurringReminderJob.reminderEventKey(1L, reminderDate)));
    }

    @Test
    void should_countDisabledAndFailedSeparately_whenNotifyQuietlyDoesNotCreate() {
        when(recurringMapper.findUpcomingPayments(any())).thenReturn(List.of(
                Map.of(
                        "recurring_id", 1L,
                        "member_id", "member-1",
                        "product_name", "사료",
                        "price", new BigDecimal("1000")),
                Map.of(
                        "recurring_id", 2L,
                        "member_id", "member-2",
                        "product_name", "간식",
                        "price", new BigDecimal("2000")),
                Map.of(
                        "recurring_id", 3L,
                        "member_id", "member-3",
                        "product_name", "패드",
                        "price", new BigDecimal("3000"))));
        when(inboxNotifier.notifyQuietly(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        InboxNotifier.Result.DISABLED,
                        InboxNotifier.Result.FAILED,
                        InboxNotifier.Result.DUPLICATE);

        RecurringReminderJob.ReminderCounts counts = job.runReminders();

        assertEquals(0, counts.created());
        assertEquals(1, counts.disabled());
        assertEquals(1, counts.duplicate());
        assertEquals(1, counts.error());
    }
}
