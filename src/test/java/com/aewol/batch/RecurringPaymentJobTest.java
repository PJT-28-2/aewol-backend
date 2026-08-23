package com.aewol.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.domain.recurring.mapper.RecurringMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecurringPaymentJobTest {

    @Mock RecurringMapper recurringMapper;
    @Mock RecurringPaymentExecutor executor;
    @Mock ScheduledJobLock scheduledJobLock;
    @InjectMocks RecurringPaymentJob job;

    @Test
    void should_skipDuePayments_whenLockIsHeld() {
        when(scheduledJobLock.runExclusive(any(), any(), any())).thenReturn(false);

        job.executeRecurringPayments();

        verify(scheduledJobLock).runExclusive(
                eq(RecurringPaymentJob.LOCK_KEY), eq(RecurringPaymentJob.LOCK_TTL), any());
        verify(recurringMapper, never()).findDuePayments(any());
        verify(executor, never()).execute(any());
    }

    @Test
    void should_executeDuePayments_whenLockIsAcquired() {
        when(scheduledJobLock.runExclusive(any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return true;
        });
        when(recurringMapper.findDuePayments(any())).thenReturn(List.of(Map.of("recurring_id", "1")));
        when(executor.execute(any())).thenReturn(true);

        job.executeRecurringPayments();

        verify(recurringMapper).findDuePayments(any());
        verify(executor).execute(any());
    }
}
