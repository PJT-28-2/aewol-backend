package com.aewol.domain.pet.job;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PetCharacterJobRunnerTest {

    @Test
    @DisplayName("종료 때 시작하지 못한 작업은 실행하지 않고 취소한다")
    void should_discardQueuedTask_withoutRunningIt_whenShuttingDown() {
        QueuedExecutor executor = new QueuedExecutor();
        PetCharacterJobRunner runner = new PetCharacterJobRunner(executor);
        AtomicBoolean ran = new AtomicBoolean();
        AtomicBoolean discarded = new AtomicBoolean();

        assertTrue(runner.submit(() -> ran.set(true), () -> discarded.set(true)));
        runner.shutdown();

        assertFalse(ran.get());
        assertTrue(discarded.get());
    }

    @Test
    @DisplayName("실행 두 건과 대기 네 건을 넘으면 새 요청을 거절한다")
    void should_rejectTask_whenSixPhotosAreAlreadyInMemory() throws Exception {
        PetCharacterJobRunner runner = new PetCharacterJobRunner();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocking = () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        try {
            assertTrue(runner.submit(blocking, () -> { }));
            assertTrue(runner.submit(blocking, () -> { }));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            for (int i = 0; i < 4; i++) {
                assertTrue(runner.submit(() -> { }, () -> { }));
            }
            assertFalse(runner.submit(() -> { }, () -> { }));
        } finally {
            release.countDown();
            runner.shutdown();
        }
    }

    private static final class QueuedExecutor extends AbstractExecutorService {
        private final List<Runnable> queued = new ArrayList<>();
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> discarded = new ArrayList<>(queued);
            queued.clear();
            return discarded;
        }

        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }
    }
}
