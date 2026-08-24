package com.aewol.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.domain.member.mapper.MemberMapper;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
class MemberRetentionCleanupJobTest {

    @Mock MemberMapper memberMapper;
    @Mock MemberRetentionCleanupExecutor executor;
    @Mock ScheduledJobLock scheduledJobLock;

    private MemberRetentionCleanupJob job;

    @BeforeEach
    void setUp() {
        job = new MemberRetentionCleanupJob(memberMapper, executor, scheduledJobLock);
    }

    @Test
    void schedulerUsesDailySeoulCronAndDistributedLock() throws Exception {
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return true;
        }).when(scheduledJobLock).runExclusive(
                eq(MemberRetentionCleanupJob.LOCK_KEY),
                eq(MemberRetentionCleanupJob.LOCK_TTL),
                org.mockito.ArgumentMatchers.any(Runnable.class));
        when(memberMapper.findRetentionCleanupCandidateIds()).thenReturn(List.of());

        job.cleanupWithdrawnMembers();

        verify(scheduledJobLock).runExclusive(
                eq(MemberRetentionCleanupJob.LOCK_KEY),
                eq(MemberRetentionCleanupJob.LOCK_TTL),
                org.mockito.ArgumentMatchers.any(Runnable.class));
        Method method = MemberRetentionCleanupJob.class.getMethod("cleanupWithdrawnMembers");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertEquals("0 30 2 * * *", scheduled.cron());
        assertEquals("Asia/Seoul", scheduled.zone());
    }

    @Test
    void oneMemberFailureDoesNotStopRemainingCandidates() {
        when(memberMapper.findRetentionCleanupCandidateIds()).thenReturn(List.of(1L, 2L, 3L));
        when(executor.execute(1L)).thenReturn(true);
        when(executor.execute(2L)).thenThrow(new RuntimeException("member cleanup failed"));
        when(executor.execute(3L)).thenReturn(false);

        job.runCleanup();

        verify(executor).execute(1L);
        verify(executor).execute(2L);
        verify(executor).execute(3L);
    }
}
