package com.aewol.batch;

import com.aewol.domain.member.mapper.MemberMapper;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 30일 복구 기간을 지난 탈퇴 회원의 identity/PII를 매일 정리한다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemberRetentionCleanupJob {

    static final String LOCK_KEY = "lock:batch:member-retention-cleanup";
    static final Duration LOCK_TTL = Duration.ofHours(2);

    private final MemberMapper memberMapper;
    private final MemberRetentionCleanupExecutor executor;
    private final ScheduledJobLock scheduledJobLock;

    @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Seoul")
    public void cleanupWithdrawnMembers() {
        scheduledJobLock.runExclusive(LOCK_KEY, LOCK_TTL, this::runCleanup);
    }

    void runCleanup() {
        List<Long> candidateIds = memberMapper.findRetentionCleanupCandidateIds();
        log.info("[Batch] 회원 retention cleanup 시작 - 대상 {}건", candidateIds.size());

        int success = 0;
        int skipped = 0;
        int error = 0;
        for (Long memberId : candidateIds) {
            try {
                if (executor.execute(memberId)) {
                    success++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                error++;
                log.error("[Batch] 회원 retention cleanup 실패 - memberId={}", memberId, e);
            }
        }

        log.info("[Batch] 회원 retention cleanup 완료 - 성공 {}건 / 스킵 {}건 / 오류 {}건",
                success, skipped, error);
    }
}
