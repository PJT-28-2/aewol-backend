package com.aewol.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 스케줄 배치가 쓰는 스레드 풀.
 *
 * <p>빈을 두지 않으면 Spring은 <b>스레드 1개짜리</b> 스케줄러를 기본으로 쓴다. 지금 배치가
 * 7개라 하나가 길어지면 나머지가 전부 그 뒤에 줄을 선다.
 *
 * <p>실제로 겹칠 수 있는 조합이 있다. 홈 인사이트 예열(04:30)은 전 회원을 순회하며 회원당
 * LLM을 1~2초씩 호출하므로, 회원이 만 명이면 서너 시간이 걸린다. 그동안 09시 정기결제가
 * 밀리고, 10분마다 도는 공동구매 환불은 아예 멈춘다 — cron 트리거는 밀린 회차를 쌓지 않고
 * 합치기 때문에 놓친 실행은 그냥 사라진다.
 *
 * <p>#303의 Redis 분산 락은 <i>인스턴스 사이</i>의 중복 실행을 막는 장치다. 여기서 막는 것은
 * <i>인스턴스 안에서</i> 잡끼리 밀어내는 문제라 서로 다른 층이다.
 */
@Configuration
public class SchedulingConfig {

    /**
     * 동시에 겹칠 수 있는 잡 수를 기준으로 잡는다.
     *
     * <p>가장 몰리는 새벽 구간이 정부24(04:00) · 인사이트 예열(04:30) · 공동구매 환불(10분마다)
     * 셋이고, 여기에 월 1회 병원 시딩(03:00)이 길어져 겹칠 수 있다. 넷을 동시에 받을 수 있도록
     * 하고 한 자리를 여유로 둔다.
     *
     * <p>잡이 늘면 이 값도 같이 올려야 한다. 부족하면 조용히 예전처럼 줄을 서기 시작한다.
     */
    private static final int POOL_SIZE = 5;

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        // 로그에서 어느 배치가 남긴 줄인지 보이게 한다. 배치는 요청 추적 id(#327)가 붙지 않는
        // 경로라 스레드 이름이 유일한 단서다.
        scheduler.setThreadNamePrefix("batch-");

        // 배포로 컨테이너가 내려갈 때 진행 중인 배치를 끊지 않는다. 정기결제가 중간에 잘리면
        // 어디까지 나갔는지 알 수 없는 상태가 된다.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        // 다만 무한정 기다리면 배포가 멈춘다. 여기서 끊기더라도 처리 단위가 항목마다
        // 독립 트랜잭션이라 반쯤 된 항목은 롤백되고, 아직 못 한 항목은 다음 주기가 다시
        // 집어간다(RecurringPaymentExecutor는 FOR UPDATE로 잠근 뒤 대상 여부를 재확인하고,
        // GroupPurchaseRefundExecutor는 조건부 UPDATE로 이미 처리된 건을 걸러낸다).
        scheduler.setAwaitTerminationSeconds(60);

        return scheduler;
    }
}
