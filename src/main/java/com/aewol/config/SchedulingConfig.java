package com.aewol.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 스케줄 배치가 쓰는 스레드 풀.
 *
 * <p>빈을 두지 않으면 Spring은 <b>스레드 1개짜리</b> 스케줄러를 기본으로 쓴다. 지금 배치가
 * 스케줄 메서드가 9개라 하나가 길어지면 나머지가 전부 그 뒤에 줄을 선다.
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
     * 등록된 배치 수만큼 잡는다.
     *
     * <p>cron 잡은 자기 자신과 겹치지 않는다. Spring은 실행이 끝난 뒤에 다음 시각을 다시
     * 계산하므로, 한 실행이 길어지면 그 사이 회차는 쌓이지 않고 합쳐진다(3초짜리 잡을
     * 매초 트리거해도 동시 실행은 1개다). 그래서 최대 동시 실행 수는 <b>서로 다른 잡의
     * 개수</b>가 상한이다.
     *
     * <p>상한에 맞추면 어떤 잡도 다른 잡을 기다리지 않는다. 대부분 놀고 있는 스레드가
     * 몇 개 생기지만, 하루 몇 번 도는 배치 때문에 09시 정기결제가 밀리는 것보다 낫다.
     *
     * <p>잡을 추가하면 이 값도 같이 올려야 한다. 주석으로만 남기면 놓치기 쉬워
     * {@code SchedulingConfigTest}가 실제 {@code @Scheduled} 개수를 세어 확인한다.
     */
    private static final int POOL_SIZE = 9;

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        // 로그에서 어느 배치가 남긴 줄인지 보이게 한다. 배치는 요청 추적 id(#327)가 붙지 않는
        // 경로라 스레드 이름이 유일한 단서다.
        scheduler.setThreadNamePrefix("batch-");

        // 배포로 컨테이너가 내려갈 때 진행 중인 배치를 끊지 않는다.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);

        // 60초는 배치 하나가 끝나기를 기다리는 시간이 아니다. 인사이트 예열은 회원이 많으면
        // 몇 시간이 걸리므로 애초에 기다릴 수 있는 값이 아니다. 이 시간이 지키는 것은
        // "처리 중인 한 건"이다.
        //
        //   정기결제·공동구매 환불 — 한 건이 밀리초 단위의 독립 트랜잭션이다. 60초면
        //     처리 중인 건을 마치고도 한참 남는다. 중간에 잘리면 롤백되고 다음 주기가
        //     같은 건을 다시 집어간다(FOR UPDATE로 잠근 뒤 대상 여부 재확인 / 조건부 UPDATE).
        //
        //   인사이트 예열 — 끊겨도 된다. 이미 만들어 둔 카드는 건너뛰므로 다음 실행이
        //     남은 회원부터 이어가고, 그 사이 홈 화면은 데이터로 만든 대체 문장을 보여준다
        //     (HomeInsightServiceImpl 참고). 문구가 덜 다듬어질 뿐 화면이 비지 않는다.
        scheduler.setAwaitTerminationSeconds(60);

        return scheduler;
    }
}
