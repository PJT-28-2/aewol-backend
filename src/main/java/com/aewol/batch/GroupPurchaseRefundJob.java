package com.aewol.batch;

import com.aewol.domain.grouppurchase.service.GroupPurchaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GroupPurchaseRefundJob {

    private final GroupPurchaseService groupPurchaseService;

    /**
     * 10분마다 — 마감이 지났는데 목표 수량을 못 채운 공동구매의 결제 완료(PAID) 참여자를 자동 환불
     */
    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
    public void refundExpiredGroupPurchases() {
        log.info("[Batch] 공동구매 마감 미달 자동 환불 시작");
        int refundedCount = groupPurchaseService.processExpiredRefunds();
        log.info("[Batch] 공동구매 마감 미달 자동 환불 완료: {}건", refundedCount);
    }
}
