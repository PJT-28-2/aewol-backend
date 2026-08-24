package com.aewol.common.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;

class DeadlockRetriesTest {

    @Test
    @DisplayName("교착이 나면 최대 횟수 안에서 다시 시도해 성공한다")
    void should_retry_when_deadlockOccurs() {
        AtomicInteger attempts = new AtomicInteger();

        String result = DeadlockRetries.execute(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new DeadlockLoserDataAccessException("Deadlock", new SQLException("1213"));
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(3, attempts.get());
    }

    @Test
    @DisplayName("교착이 아닌 예외는 재시도하지 않는다")
    void should_notRetry_when_exceptionIsNotDeadlock() {
        AtomicInteger attempts = new AtomicInteger();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                DeadlockRetries.execute(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("not a deadlock");
                }));

        assertEquals("not a deadlock", thrown.getMessage());
        assertEquals(1, attempts.get());
    }

    @Test
    void should_detectMysqlDeadlockMessage() {
        assertTrue(DeadlockRetries.isDeadlock(new RuntimeException("Deadlock found when trying to get lock")));
        assertTrue(DeadlockRetries.isDeadlock(new SQLException("1213")));
        assertFalse(DeadlockRetries.isDeadlock(new IllegalStateException("other")));
    }
}
