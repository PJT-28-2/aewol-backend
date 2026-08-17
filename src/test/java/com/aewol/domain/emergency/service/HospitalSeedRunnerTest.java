package com.aewol.domain.emergency.service;

import com.aewol.external.animalhospital.AnimalHospitalClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HospitalSeedRunnerTest {

    @Mock HospitalSeedService hospitalSeedService;
    @Mock AnimalHospitalClient animalHospitalClient;
    @Mock ExecutorService executor;

    private HospitalSeedRunner runner() {
        return new HospitalSeedRunner(hospitalSeedService, animalHospitalClient, executor);
    }

    /**
     * 제출된 작업을 호출 스레드에서 즉시 실행한다. 백그라운드 스레드를 띄우지 않아야 테스트가
     * 결정적으로 동작하고, 작업이 끝난 뒤의 플래그 상태까지 이어서 검증할 수 있다.
     */
    private void runSubmittedTaskInline() {
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any());
    }

    @Test
    @DisplayName("service-key가 없으면 NOT_CONFIGURED를 반환하고 시딩을 제출하지 않는다")
    void should_returnNotConfigured_when_serviceKeyIsMissing() {
        when(animalHospitalClient.isConfigured()).thenReturn(false);

        assertEquals(HospitalSeedRunner.StartResult.NOT_CONFIGURED, runner().start());

        verifyNoInteractions(executor, hospitalSeedService);
    }

    @Test
    @DisplayName("정상 요청은 STARTED를 반환하고 백그라운드에서 시딩을 실행한다")
    void should_returnStartedAndRunSync_when_requestIsValid() {
        when(animalHospitalClient.isConfigured()).thenReturn(true);
        when(hospitalSeedService.syncHospitals()).thenReturn(42);
        runSubmittedTaskInline();

        assertEquals(HospitalSeedRunner.StartResult.STARTED, runner().start());

        verify(hospitalSeedService).syncHospitals();
    }

    @Test
    @DisplayName("이미 실행 중이면 ALREADY_RUNNING을 반환하고 시딩을 중복 제출하지 않는다")
    void should_returnAlreadyRunning_when_previousRunIsStillInFlight() {
        when(animalHospitalClient.isConfigured()).thenReturn(true);
        // executor mock의 execute()는 기본적으로 아무 것도 하지 않으므로, 제출된 작업이 끝나지
        // 않은(=플래그가 해제되지 않은) 실행 중 상태를 그대로 재현한다.
        HospitalSeedRunner runner = runner();

        assertEquals(HospitalSeedRunner.StartResult.STARTED, runner.start());
        assertEquals(HospitalSeedRunner.StartResult.ALREADY_RUNNING, runner.start());

        verify(executor, times(1)).execute(any());
        verify(hospitalSeedService, never()).syncHospitals();
    }

    @Test
    @DisplayName("시딩이 완료되면 플래그가 해제되어 다시 실행할 수 있다")
    void should_allowRerun_when_previousRunCompleted() {
        when(animalHospitalClient.isConfigured()).thenReturn(true);
        when(hospitalSeedService.syncHospitals()).thenReturn(1);
        runSubmittedTaskInline();
        HospitalSeedRunner runner = runner();

        assertEquals(HospitalSeedRunner.StartResult.STARTED, runner.start());
        assertEquals(HospitalSeedRunner.StartResult.STARTED, runner.start());

        verify(hospitalSeedService, times(2)).syncHospitals();
    }

    @Test
    @DisplayName("시딩이 예외로 실패해도 호출자에게 전파되지 않고 플래그가 해제된다")
    void should_swallowExceptionAndReleaseFlag_when_syncFails() {
        when(animalHospitalClient.isConfigured()).thenReturn(true);
        when(hospitalSeedService.syncHospitals()).thenThrow(new IllegalStateException("외부 API 장애"));
        runSubmittedTaskInline();
        HospitalSeedRunner runner = runner();

        // 백그라운드 작업의 예외는 start()로 새어나오지 않는다
        assertEquals(HospitalSeedRunner.StartResult.STARTED, runner.start());
        // 실패 후에도 재실행이 가능해야 한다 (플래그가 true로 남으면 이후 요청이 영구히 409가 된다)
        assertEquals(HospitalSeedRunner.StartResult.STARTED, runner.start());

        verify(hospitalSeedService, times(2)).syncHospitals();
    }

    @Test
    @DisplayName("작업 제출 자체가 거부되면 플래그를 되돌리고 예외를 전파한다")
    void should_rollbackFlag_when_executorRejectsTask() {
        when(animalHospitalClient.isConfigured()).thenReturn(true);
        doThrow(new RejectedExecutionException("executor 종료됨")).when(executor).execute(any());
        HospitalSeedRunner runner = runner();

        assertThrows(RejectedExecutionException.class, runner::start);

        // 플래그가 되돌려졌으므로 다음 요청은 ALREADY_RUNNING이 아니라 다시 제출을 시도한다
        assertThrows(RejectedExecutionException.class, runner::start);
        verify(executor, times(2)).execute(any());
    }

    @Test
    @DisplayName("컨텍스트 종료 시 executor를 정리한다")
    void should_shutdownExecutor_when_contextIsDestroyed() {
        runner().shutdown();

        verify(executor).shutdownNow();
    }
}
