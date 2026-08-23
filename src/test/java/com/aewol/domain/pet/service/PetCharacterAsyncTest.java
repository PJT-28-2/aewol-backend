package com.aewol.domain.pet.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.storage.FileStorage;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.pet.job.PetCharacterJob;
import com.aewol.domain.pet.job.PetCharacterJobRunner;
import com.aewol.domain.pet.job.PetCharacterJobStore;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.external.gemini.GeminiImageClient;
import com.aewol.common.util.ChromaKeyRemover;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 캐릭터 생성을 요청 스레드에서 떼어낸 뒤의 동작.
 *
 * <p>생성은 Gemini를 두 번 부르느라 20~25초가 걸린다. 요청이 끝날 때까지 기다리면 톰캣
 * 스레드가 그동안 묶이고 앞단 프록시 타임아웃에도 걸릴 수 있다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PetCharacterAsyncTest {

    @Mock GeminiImageClient geminiImageClient;
    @Mock ChromaKeyRemover chromaKeyRemover;
    @Mock FileStorage fileStorage;
    @Mock PetMapper petMapper;
    @Mock RedisRateLimiter rateLimiter;
    @Mock PetCharacterJobStore jobStore;

    private final List<PetCharacterJob> saved = new ArrayList<>();

    private PetCharacterServiceImpl service(ExecutorService executor) {
        PetCharacterServiceImpl service = new PetCharacterServiceImpl(
                geminiImageClient, chromaKeyRemover, fileStorage, petMapper, rateLimiter,
                new PetCharacterJobRunner(executor), jobStore);
        ReflectionTestUtils.setField(service, "dailyLimit", 5);
        return service;
    }

    @BeforeEach
    void setUp() {
        Map<String, Object> pet = new HashMap<>();
        pet.put("pet_id", "pet-1");
        pet.put("member_id", "member-1");
        when(petMapper.findByIdAndMemberId("pet-1", "member-1")).thenReturn(pet);
        when(geminiImageClient.isConfigured()).thenReturn(true);
        when(rateLimiter.incrementWithExpiry(anyString(), anyLong())).thenReturn(1L);

        doAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return null;
        }).when(jobStore).save(any());
        when(jobStore.find(anyString()))
                .thenAnswer(invocation -> saved.stream()
                        .filter(j -> j.getJobId().equals(invocation.getArgument(0)))
                        .reduce((a, b) -> b).orElse(null));
    }

    private MultipartFile photo() {
        return new MockMultipartFile("photo", "dog.png", "image/png", "bytes".getBytes());
    }

    /** 같은 스레드에서 곧바로 실행한다. */
    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
            @Override public void execute(Runnable command) { command.run(); }
        };
    }

    /** 아무것도 받지 않는다. 대기열이 가득 찬 상황. */
    private static ExecutorService rejectingExecutor() {
        return new AbstractExecutorService() {
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
            @Override public void execute(Runnable command) { throw new RejectedExecutionException(); }
        };
    }

    /** 접수만 하고 실행하지 않는다. 아직 만들고 있는 상황. */
    private static ExecutorService neverRunningExecutor() {
        return new AbstractExecutorService() {
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
            @Override public void execute(Runnable command) { /* 붙잡아 둔다 */ }
        };
    }

    /*
     * 이 메서드의 존재 이유다. 20초 넘게 걸리는 생성이 끝나기를 기다리지 않고 돌아와야 한다.
     */
    @Test
    @DisplayName("생성을 기다리지 않고 곧바로 접수 결과를 돌려준다")
    void should_returnImmediately_withoutWaitingForGeneration() {
        PetCharacterJob job = service(neverRunningExecutor()).submit("member-1", "pet-1", photo());

        assertEquals(PetCharacterJob.Status.RUNNING, job.getStatus());
        assertNotNull(job.getJobId());
        // 아직 Gemini를 부르지 않았다. 불렀다면 그만큼 기다렸다는 뜻이다.
        verify(geminiImageClient, never()).generate(any(), anyString(), anyString());
    }

    /*
     * 접수해 놓고 나중에 "사실 안 되는 요청이었다"고 답하면 사용자는 그동안 기다린 셈이 된다.
     */
    @Test
    @DisplayName("남의 반려동물이면 접수하지 않고 바로 거절한다")
    void should_rejectUpfront_when_petNotOwned() {
        when(petMapper.findByIdAndMemberId("pet-2", "member-1")).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> service(directExecutor()).submit("member-1", "pet-2", photo()));

        verify(jobStore, never()).save(any());
        verify(rateLimiter, never()).incrementWithExpiry(anyString(), anyLong());
    }

    @Test
    @DisplayName("끝나면 결과를 상태로 남긴다")
    void should_recordResult_when_generationSucceeds() {
        when(geminiImageClient.generate(any(), anyString(), anyString()))
                .thenReturn("fullbody".getBytes(), "profile".getBytes());
        when(chromaKeyRemover.removeGreenBackground(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(fileStorage.store(any(), eq("pet-character"), eq("png")))
                .thenReturn("pet-character/full.png", "pet-character/face.png");
        when(fileStorage.signedUrl(anyString()))
                .thenAnswer(invocation -> "signed:" + invocation.getArgument(0));
        when(petMapper.updateCharacterImages(any(), any(), any(), any())).thenReturn(1);

        PetCharacterJob job = service(directExecutor()).submit("member-1", "pet-1", photo());
        PetCharacterJob finished = jobStore.find(job.getJobId());

        assertEquals(PetCharacterJob.Status.DONE, finished.getStatus());
        assertEquals("signed:pet-character/face.png", finished.getProfileImg());
        assertEquals("signed:pet-character/full.png", finished.getCharacterImg());
    }

    /*
     * 실패를 상태로 남기지 않으면 작업이 RUNNING인 채로 남아 사용자는 끝나지 않는 화면을 본다.
     */
    @Test
    @DisplayName("실패해도 이유를 상태로 남긴다")
    void should_recordFailure_when_generationFails() {
        when(geminiImageClient.generate(any(), anyString(), anyString())).thenReturn(null);

        PetCharacterJob job = service(directExecutor()).submit("member-1", "pet-1", photo());
        PetCharacterJob finished = jobStore.find(job.getJobId());

        assertEquals(PetCharacterJob.Status.FAILED, finished.getStatus());
        assertNotNull(finished.getMessage());
    }

    /*
     * 접수하지 못했으면 오늘 몫을 되돌려야 한다. 그대로 두면 사용자는 아무것도 받지 못하고
     * 횟수만 잃는다.
     */
    @Test
    @DisplayName("대기열이 가득 차면 차감한 할당량을 되돌린다")
    void should_rollbackQuota_when_queueFull() {
        assertThrows(BusinessException.class,
                () -> service(rejectingExecutor()).submit("member-1", "pet-1", photo()));

        verify(rateLimiter).rollback(anyString());
    }

    // 남의 작업은 존재 여부부터 알리지 않는다.
    @Test
    @DisplayName("남의 작업은 조회되지 않는다")
    void should_hideJob_when_requestedByAnotherMember() {
        PetCharacterServiceImpl service = service(neverRunningExecutor());
        PetCharacterJob job = service.submit("member-1", "pet-1", photo());

        assertNotNull(service.findJob("member-1", job.getJobId()));
        assertNull(service.findJob("member-2", job.getJobId()));
    }

    /*
     * 실패 상태를 만들 때 저장소에서 작업을 다시 읽으면, 그 조회가 실패했을 때(Redis 장애·
     * TTL 만료) 정작 실패를 남기지 못한다. 게다가 그 예외는 executor 안에서 아무도 잡지
     * 않아 작업이 RUNNING인 채로 남고, 사용자는 끝나지 않는 화면을 본다.
     */
    @Test
    @DisplayName("저장소를 읽지 못해도 실패는 상태로 남는다")
    void should_recordFailure_evenWhenStoreCannotBeRead() {
        when(geminiImageClient.generate(any(), anyString(), anyString())).thenReturn(null);
        // 접수 상태 저장 이후로는 조회가 되지 않는 상황
        when(jobStore.find(anyString())).thenReturn(null);

        PetCharacterJob job = service(directExecutor()).submit("member-1", "pet-1", photo());

        PetCharacterJob last = saved.get(saved.size() - 1);
        assertEquals(job.getJobId(), last.getJobId());
        assertEquals(PetCharacterJob.Status.FAILED, last.getStatus());
        assertEquals("member-1", last.getMemberId());
    }

    /*
     * 상태를 남기는 것마저 실패하면 더 할 수 있는 일이 없다. 그렇더라도 예외가 executor
     * 밖으로 나가면 안 된다 — 아무도 잡지 않아 스레드만 죽는다.
     */
    @Test
    @DisplayName("실패 상태 저장까지 실패해도 예외를 밖으로 흘리지 않는다")
    void should_notPropagate_whenSavingFailureAlsoFails() {
        when(geminiImageClient.generate(any(), anyString(), anyString())).thenReturn(null);
        doAnswer(invocation -> {
            PetCharacterJob job = invocation.getArgument(0);
            saved.add(job);
            if (job.getStatus() == PetCharacterJob.Status.FAILED) {
                throw new IllegalStateException("redis down");
            }
            return null;
        }).when(jobStore).save(any());

        assertDoesNotThrow(() -> service(directExecutor()).submit("member-1", "pet-1", photo()));
    }

    /*
     * 접수 상태를 남기지 못하면 사용자가 결과를 물어볼 자리가 없다. 시작하지 않고 차감한
     * 몫을 되돌려야 한다.
     */
    @Test
    @DisplayName("접수 상태를 남기지 못하면 할당량을 되돌리고 거절한다")
    void should_rollbackQuota_when_acceptedStateCannotBeSaved() {
        doThrow(new IllegalStateException("redis down")).when(jobStore).save(any());

        assertThrows(BusinessException.class,
                () -> service(directExecutor()).submit("member-1", "pet-1", photo()));

        verify(rateLimiter).rollback(anyString());
        verify(geminiImageClient, never()).generate(any(), anyString(), anyString());
    }
}
