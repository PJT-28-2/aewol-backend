package com.aewol.batch;

import com.aewol.domain.insight.mapper.HomeInsightMapper;
import com.aewol.domain.insight.service.HomeInsightService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 홈 인사이트 카드를 미리 만들어 둔다.
 *
 * <p>카드 생성은 외부 LLM 호출이라 1~2초가 걸린다. 홈을 여는 순간에 만들면 그 시간이
 * 그대로 사용자 대기로 간다. 정부24 동기화(04:00) 뒤에 돌려 그날 갱신된 지원사업이
 * 카드에 반영되게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HomeInsightWarmUpJob {

    private final HomeInsightService homeInsightService;
    private final HomeInsightMapper homeInsightMapper;

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void warmUp() {
        List<String> memberIds;
        try {
            memberIds = homeInsightMapper.findWarmUpTargetMemberIds();
        } catch (RuntimeException e) {
            log.error("[Batch] 홈 인사이트 예열 대상 조회 실패: {}", e.getMessage(), e);
            return;
        }

        int generated = 0;
        for (String memberId : memberIds) {
            try {
                generated += homeInsightService.warmUp(memberId, null);
            } catch (Exception e) {
                // 한 회원의 실패가 나머지 예열을 멈추지 않게 한다.
                log.warn("[Batch] 홈 인사이트 예열 실패 - memberId: {}, reason: {}", memberId, e.getMessage());
            }
        }
        log.info("[Batch] 홈 인사이트 예열 완료 - 회원 {}명, 카드 {}건 생성", memberIds.size(), generated);
    }
}
