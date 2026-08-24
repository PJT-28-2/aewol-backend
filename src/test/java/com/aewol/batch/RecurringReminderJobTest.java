package com.aewol.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.domain.notification.service.InboxNotifier;
import com.aewol.domain.recurring.mapper.RecurringMapper;
import java.math.BigDecimal;
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
        verify(inboxNotifier, never()).notifyQuietly(any(), any(), any(), any(), any(), any());
    }

    @Test
    void should_notifyUpcomingPayments_whenLockIsAcquired() {
        when(scheduledJobLock.runExclusive(any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return true;
        });
        when(recurringMapper.findUpcomingPayments(any())).thenReturn(List.of(Map.of(
                "recurring_id", 1L,
                "member_id", "member-1",
                "product_name", "강아지 사료",
                "price", new BigDecimal("32000"))));

        job.remindUpcomingPayments();

        verify(recurringMapper).findUpcomingPayments(any());
        verify(inboxNotifier).notifyQuietly(
                eq("member-1"),
                eq(InboxNotifier.Channel.RECURRING),
                eq("RECURRING"),
                eq("정기결제가 3일 뒤예요"),
                eq("강아지 사료 32000원이 예정되어 있어요."),
                eq("/payment/recurring"));
    }
}
