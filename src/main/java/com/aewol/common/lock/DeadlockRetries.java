package com.aewol.common.lock;

import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * InnoDB 교착(1213)과 잠금 대기 초과를 짧은 백오프로 재시도한다.
 * 일일 절삭과 월말 자동기부가 같은 회원 행을 겹쳐 잡을 때, 한쪽이 DB에 의해
 * 롤백되어 그 회원만 누락되지 않게 하기 위함이다.
 */
@Slf4j
public final class DeadlockRetries {

    public static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_MS = {50L, 150L, 400L};

    private DeadlockRetries() {
    }

    public static <T> T execute(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException exception) {
                if (attempt >= MAX_ATTEMPTS || !isDeadlock(exception)) {
                    throw exception;
                }
                log.warn("DB 교착이 발생해 {}번째 재시도합니다.", attempt + 1, exception);
                sleep(BACKOFF_MS[Math.min(attempt - 1, BACKOFF_MS.length - 1)]);
            }
        }
    }

    static boolean isDeadlock(Throwable throwable) {
        while (throwable != null) {
            if (throwable instanceof PessimisticLockingFailureException) {
                return true;
            }
            String message = throwable.getMessage();
            if (message != null && (message.contains("Deadlock") || message.contains("1213"))) {
                return true;
            }
            throwable = throwable.getCause();
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("교착 재시도 대기 중 인터럽트되었습니다.", interrupted);
        }
    }
}
