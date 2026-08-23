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
    /*
     * 10분마다 도는데 TTL이 15분이면, 한 번 실행이 15분을 넘길 때 TTL이 먼저 풀려
     * 다음 주기가 락을 새로 잡는다. 두 실행이 겹칠 수 있다는 뜻이다.
     *
     * 겹쳐도 이중 환불은 나지 않는다. cancelParticipant가 CANCELLED가 아닌 행만
     * 갱신하는 조건부 UPDATE라, 뒤늦은 쪽은 영향 행 0으로 스킵된다 — 실제 안전장치는
     * 이 멱등성이고 락은 헛일을 줄이는 장치다.
     *
     * 그래도 겹치는 상황 자체를 드물게 두는 편이 낫다. 대상 건수가 늘면 실행이 길어질 수
     * 있으므로 주기의 세 배로 잡는다. 인스턴스가 중간에 죽으면 그만큼 락이 남지만,
     * 환불은 다음 주기가 다시 집어가므로 감당할 수 있다.
     */
    static final Duration LOCK_TTL = Duration.ofMinutes(30);

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
