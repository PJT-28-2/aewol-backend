package com.aewol.batch;

import com.aewol.domain.grouppurchase.mapper.GroupPurchaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GroupPurchaseRefundJob {

    static final String LOCK_KEY = "lock:batch:group-purchase-refund";
    static final Duration LOCK_TTL = Duration.ofMinutes(15);

    private final GroupPurchaseMapper groupPurchaseMapper;
    private final GroupPurchaseRefundExecutor executor;
    private final ScheduledJobLock scheduledJobLock;

    /**
     * 10분마다 — 마감이 지났는데 목표 수량을 못 채운 공동구매의 결제 완료(PAID) 참여자를 자동 환불.
     * 각 건은 독립 트랜잭션(GroupPurchaseRefundExecutor)으로 처리해 한 건의 실패가 나머지를 막지 않게 한다.
     */
    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
    public void refundExpiredGroupPurchases() {
        scheduledJobLock.runExclusive(LOCK_KEY, LOCK_TTL, this::runRefunds);
    }

    private void runRefunds() {
        List<Map<String, Object>> candidates = groupPurchaseMapper.findExpiredUnfulfilledPaidParticipants();
        log.info("[Batch] 공동구매 마감 미달 자동 환불 시작 — 대상 {}건", candidates.size());

        int success = 0, skipped = 0, error = 0;
        for (Map<String, Object> candidate : candidates) {
            try {
                if (executor.execute(candidate)) {
                    success++;
                } else {
                    skipped++;
                    log.warn("[Batch] 이미 처리된 참여자라 스킵 — gpId={}, memberId={}",
                            candidate.get("gp_id"), candidate.get("member_id"));
                }
            } catch (Exception e) {
                error++;
                log.error("[Batch] 공동구매 자동 환불 처리 실패 — gpId={}, memberId={}",
                        candidate.get("gp_id"), candidate.get("member_id"), e);
            }
        }

        log.info("[Batch] 공동구매 마감 미달 자동 환불 완료 — 성공 {}건 / 스킵 {}건 / 오류 {}건",
                success, skipped, error);
    }
}
