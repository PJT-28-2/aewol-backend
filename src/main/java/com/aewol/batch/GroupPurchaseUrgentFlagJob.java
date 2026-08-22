package com.aewol.batch;

import com.aewol.domain.grouppurchase.mapper.GroupPurchaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GroupPurchaseUrgentFlagJob {

    private final GroupPurchaseMapper groupPurchaseMapper;

    /**
     * 매일 자정 1분 뒤 — is_urgent_active=1인데 이미 deadline이 지난 공동구매를 0으로 내린다.
     * 참여/취소로 인한 수량 변화는 GroupPurchaseServiceImpl#join의 updateQuantity가 그 트랜잭션
     * 안에서 즉시 반영하므로, 이 배치는 "시간이 지나서" 바뀌는 쪽만 담당한다 — 그 행에 대한
     * 쓰기 이벤트가 없어 DB 트리거로는 감지할 수 없는 전환이다.
     * 프론트가 deadline을 항상 그날 23:59:59로 보내고 GroupPurchaseServiceImpl#create가 저장
     * 시점에 이를 강제하므로(자정 경계에서만 전환이 일어난다는 불변식), 하루 1회 실행으로 충분하다.
     * 자정 정각이 아니라 1분 뒤로 잡은 것은 자정에 걸친 요청과의 레이스를 피하기 위해서다.
     */
    @Scheduled(cron = "0 1 0 * * *", zone = "Asia/Seoul")
    public void deactivateExpiredUrgentFlags() {
        int updated = groupPurchaseMapper.deactivateExpiredUrgentFlags();
        log.info("[Batch] 공동구매 is_urgent_active 자정 갱신 완료 — {}건", updated);
    }
}
