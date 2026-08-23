package com.aewol.batch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 잔돈 적립 배치의 트랜잭션 경계를 지킨다(#349, PaymentTransactionBoundaryTest와 동일한 접근).
 *
 * <p>Spring @Transactional은 프록시 기반이라, 트랜잭션 밖에서(=@Transactional이 없는 호출자에서)
 * @Transactional 메서드를 호출할 때마다 매번 새 트랜잭션을 연다(기본 전파 REQUIRED). 그래서
 * "DonationRoundUpExecutor.execute()에만 @Transactional이 있고, 그걸 호출하는 DonationRoundUpJob의
 * for 루프 쪽에는 없다"는 구조 자체가 "후보 1건마다 독립된 트랜잭션"을 보장한다 — 실제로 N건을
 * DB에 넣고 한 건을 실패시켜 롤백 범위를 확인하는 통합테스트 없이도, 이 구조만 고정하면 같은
 * 결론이 항상 성립한다. 반대로 이 구조가 깨지면(예: 루프 쪽에 @Transactional이 다시 붙거나,
 * Job이 executor 없이 직접 처리 로직을 갖게 되면) 부분 실패가 전체 롤백으로 되돌아갈 수 있으므로,
 * 주석이 아니라 이 테스트로 그 사실을 고정한다.
 */
class DonationRoundUpTransactionBoundaryTest {

    @Test
    @DisplayName("잔돈 적립 후보 1건 처리에는 @Transactional이 있다 — 이게 건별 독립 트랜잭션의 근거다")
    void should_beTransactional_atExecutorExecute() throws Exception {
        Method execute = DonationRoundUpExecutor.class.getMethod("execute", Map.class);

        assertNotNull(execute.getAnnotation(Transactional.class),
                "execute()가 트랜잭션이 아니면 배치 전체가 스케줄러 스레드에서 트랜잭션 없이 돌거나, "
                        + "다시 호출자 쪽 트랜잭션에 얹혀 부분 실패가 전체 롤백으로 되돌아갈 수 있다.");
    }

    /*
     * 루프 쪽에 @Transactional이 붙으면 그 순간부터 executor.execute()의 self-invocation과
     * 무관하게(별도 빈이라 self-invocation 문제는 없지만) 바깥 트랜잭션에 참여하게 되어,
     * 한 건의 예외가 이미 커밋됐어야 할 이전 건들까지 롤백시킨다 — processDailyRoundUps가
     * 원래 겪던 문제 그대로다.
     */
    @Test
    @DisplayName("잔돈 적립 배치 루프(Job)에는 @Transactional이 없다 — 있으면 부분 실패가 전체 롤백으로 되돌아간다")
    void should_notBeTransactional_atJobLoop() {
        assertNull(DonationRoundUpJob.class.getAnnotation(Transactional.class),
                "클래스에 붙으면 루프를 도는 public 메서드까지 트랜잭션이 걸린다.");

        for (Method method : DonationRoundUpJob.class.getDeclaredMethods()) {
            assertNull(method.getAnnotation(Transactional.class),
                    "DonationRoundUpJob." + method.getName() + "()에 @Transactional이 있으면 안 된다 — "
                            + "후보를 순회하는 쪽이 트랜잭션을 열면 executor.execute()가 그 트랜잭션에 "
                            + "참여하게 되어 건별 독립 트랜잭션이 깨진다.");
        }
    }

    // processDailyRoundUps가 DonationServiceImpl에 남아있으면(죽은 코드 또는 다른 경로에서 재사용)
    // 언젠가 다시 호출될 위험이 있고, 이 클래스가 원래 배치 전체를 단일 트랜잭션으로 묶던 원인이었다.
    @Test
    @DisplayName("DonationServiceImpl에는 잔돈 적립 배치 로직이 더 이상 없다 — DonationRoundUpExecutor로 완전히 옮겨졌다")
    void should_haveNoRoundUpLogic_inDonationServiceImpl() {
        boolean stillHasRoundUpMethod = java.util.Arrays
                .stream(com.aewol.domain.donation.service.DonationServiceImpl.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().toLowerCase().contains("roundup")
                        && !m.getName().toLowerCase().contains("autodonat"));

        assertFalse(stillHasRoundUpMethod,
                "processDailyRoundUps류 메서드가 DonationServiceImpl에 남아있다 — "
                        + "DonationRoundUpJob/Executor로 완전히 옮겨졌어야 한다.");
    }

    @Test
    @DisplayName("DonationRoundUpExecutor.execute()는 public이다 — Spring AOP 프록시가 @Transactional을 적용하려면 public이어야 한다")
    void should_bePublic_forProxyToApply() throws Exception {
        Method execute = DonationRoundUpExecutor.class.getMethod("execute", Map.class);

        assertTrue(Modifier.isPublic(execute.getModifiers()),
                "Spring의 기본 CGLIB/JDK 동적 프록시는 public 메서드에만 어드바이스를 적용한다. "
                        + "private/protected/package-private면 @Transactional이 조용히 무시된다.");
    }
}
