package com.aewol.domain.pet.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.ChromaKeyRemover;
import com.aewol.common.storage.FileStorage;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.pet.dto.PetCharacterResponse;
import com.aewol.domain.pet.job.PetCharacterJob;
import com.aewol.domain.pet.job.PetCharacterJobRunner;
import com.aewol.domain.pet.job.PetCharacterJobStore;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.external.gemini.GeminiImageClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 반려동물 사진으로 3D 캐릭터 이미지를 만든다.
 *
 * <p>두 단계로 나뉜다. 사진에서 전신 캐릭터를 만들고, 그 캐릭터를 다시 입력으로 넣어
 * 정면 얼굴을 만든다. 2단계가 1단계 결과를 참조해야 같은 캐릭터로 유지된다.
 *
 * <p>생성 호출은 두 단계를 합쳐 20초 이상 걸린다. 이 클래스에 트랜잭션을 걸지 않는 것은
 * 의도된 것이다. 느린 외부 호출을 트랜잭션 안에 두면 그동안 DB 커넥션이 점유돼 풀이
 * 고갈될 수 있다. DB 반영은 UPDATE 한 문장뿐이라 그 자체로 원자적이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PetCharacterServiceImpl implements PetCharacterService {

    private static final Set<String> ALLOWED_TYPES =
            Set.of("image/png", "image/jpeg", "image/jpg", "image/webp");
    private static final long MAX_PHOTO_BYTES = 10L * 1024 * 1024;
    private static final String UPLOAD_SUB_DIR = "pet-character";
    private static final String RATE_LIMIT_PREFIX = "petCharacter:";
    private static final String QUEUE_FULL_MESSAGE =
            "지금은 만들고 있는 캐릭터가 많아요. 잠시 후 다시 시도해 주세요.";
    private static final String SHUTDOWN_MESSAGE =
            "서버 업데이트로 캐릭터 생성을 마치지 못했어요. 다시 시도해 주세요.";

    private final GeminiImageClient geminiImageClient;
    private final ChromaKeyRemover chromaKeyRemover;
    private final FileStorage fileStorage;
    private final PetMapper petMapper;
    private final RedisRateLimiter rateLimiter;
    private final PetCharacterJobRunner jobRunner;
    private final PetCharacterJobStore jobStore;

    /** 호출마다 실제 비용이 나가므로 1인당 하루 횟수를 제한한다. */
    @Value("${external.gemini.image-daily-limit:5}")
    private int dailyLimit;

    private String fullbodyPrompt;
    private String profilePrompt;

    @Override
    @Deprecated
    public PetCharacterResponse generate(String memberId, String petId, MultipartFile photo) {
        Prepared prepared = prepare(memberId, petId, photo);
        return produce(prepared);
    }

    @Override
    public PetCharacterJob submit(String memberId, String petId, MultipartFile photo) {
        Prepared prepared = prepare(memberId, petId, photo);
        String jobId = UUID.randomUUID().toString();

        PetCharacterJob accepted = PetCharacterJob.builder()
                .jobId(jobId)
                .memberId(memberId)
                .petId(petId)
                .status(PetCharacterJob.Status.RUNNING)
                .build();
        try {
            jobStore.save(accepted);
        } catch (RuntimeException e) {
            // 접수 상태를 남기지 못하면 사용자가 결과를 물어볼 자리가 없다. 시작하지 않고
            // 차감한 몫을 되돌린다.
            rateLimiter.rollback(prepared.quotaKey);
            throw BusinessException.conflict("지금은 캐릭터를 만들 수 없어요. 잠시 후 다시 시도해 주세요.");
        }

        boolean queued = jobRunner.submit(
                () -> run(jobId, prepared),
                () -> discardBeforeStart(jobId, prepared));
        if (!queued) {
            // 접수하지 못했으니 방금 차감한 오늘 몫을 되돌린다. 그대로 두면 사용자는
            // 아무것도 받지 못하고 횟수만 잃는다.
            rateLimiter.rollback(prepared.quotaKey);
            recordFailure(jobId, prepared, QUEUE_FULL_MESSAGE, null);
            throw BusinessException.conflict(QUEUE_FULL_MESSAGE);
        }
        return accepted;
    }

    @Override
    public PetCharacterJob findJob(String memberId, String jobId) {
        PetCharacterJob job = jobStore.find(jobId);
        if (job == null || !job.getMemberId().equals(memberId)) {
            // 남의 작업은 존재 여부부터 알리지 않는다.
            return null;
        }
        return job;
    }

    /**
     * 백그라운드에서 실제 생성을 돌리고 결과를 남긴다.
     *
     * <p>여기서 예외가 밖으로 나가면 아무도 잡지 않아 작업이 RUNNING인 채로 남는다.
     * 사용자는 끝나지 않는 화면을 보게 되므로 실패도 반드시 상태로 남긴다.
     */
    private void run(String jobId, Prepared prepared) {
        try {
            PetCharacterResponse result = produce(prepared);
            jobStore.save(PetCharacterJob.builder()
                    .jobId(jobId)
                    .memberId(prepared.memberId)
                    .petId(prepared.petId)
                    .status(PetCharacterJob.Status.DONE)
                    .profileImg(result.getProfileImg())
                    .characterImg(result.getCharacterImg())
                    .remainingToday(result.getRemainingToday())
                    .build());
        } catch (BusinessException e) {
            recordFailure(jobId, prepared, e.getMessage(), e);
        } catch (Throwable e) {
            boolean interrupted = isInterruption(e);
            if (interrupted) {
                rateLimiter.rollback(prepared.quotaKey);
            }
            recordFailure(jobId, prepared,
                    interrupted ? SHUTDOWN_MESSAGE : "캐릭터를 만들지 못했어요. 잠시 후 다시 시도해 주세요.",
                    e);
            if (e instanceof Error) {
                // 상태는 남기되 손상됐을 수 있는 executor 스레드는 정상인 것처럼 재사용하지 않는다.
                throw (Error) e;
            }
        }
    }

    /** 배포 종료로 큐에서 시작하지 못한 작업을 실패로 바꾸고 사용 횟수를 돌려준다. */
    private void discardBeforeStart(String jobId, Prepared prepared) {
        rateLimiter.rollback(prepared.quotaKey);
        recordFailure(jobId, prepared, SHUTDOWN_MESSAGE, null);
    }

    private boolean isInterruption(Throwable cause) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException || current instanceof java.io.InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 실패를 상태로 남긴다.
     *
     * <p>여기서 예외가 밖으로 나가면 아무도 잡지 않아 작업이 RUNNING인 채로 남는다. 사용자는
     * 끝나지 않는 화면을 보게 되므로, 상태를 남기지 못하는 상황까지 여기서 삼킨다.
     *
     * <p>실패 상태는 접수 때 이미 알고 있는 값으로만 만든다. 저장소에서 다시 읽어 쓰면
     * 그 조회가 실패했을 때(Redis 장애·TTL 만료) 정작 실패를 남기지 못한다.
     */
    private void recordFailure(String jobId, Prepared prepared, String message, Throwable cause) {
        log.error("[PET_CHARACTER_JOB_FAILED] 캐릭터 생성 실패 - jobId: {}", jobId, cause);
        try {
            jobStore.save(PetCharacterJob.builder()
                    .jobId(jobId)
                    .memberId(prepared.memberId)
                    .petId(prepared.petId)
                    .status(PetCharacterJob.Status.FAILED)
                    .message(message)
                    .build());
        } catch (Throwable e) {
            // 남기지 못하면 화면은 TTL이 지나 작업이 사라질 때까지 기다린다. 더 할 수 있는
            // 일이 없으므로 조사할 수 있게 남기고 끝낸다.
            log.error("[PET_CHARACTER_JOB_STATE_LOST] 실패 상태를 남기지 못했습니다. jobId: {}", jobId, e);
        }
    }

    /**
     * 요청을 받아들일 수 있는지 확인하고 생성에 필요한 것만 남긴다.
     *
     * <p>사진은 여기서 바이트로 읽어 둔다. {@code MultipartFile}은 요청에 매여 있어 응답이
     * 끝난 뒤 백그라운드에서 읽으면 이미 정리된 임시 파일을 보게 된다.
     */
    private Prepared prepare(String memberId, String petId, MultipartFile photo) {
        requireMemberId(memberId);
        Map<String, Object> pet = petMapper.findByIdAndMemberId(petId, memberId);
        if (pet == null) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
        validatePhoto(photo);
        if (!geminiImageClient.isConfigured()) {
            throw new BusinessException("AI 이미지 생성이 아직 준비되지 않았어요.");
        }

        String quotaKey = quotaKey(memberId);
        long used = consumeDailyQuota(quotaKey);

        return new Prepared(memberId, petId, pet, readBytes(photo), photo.getContentType(), used, quotaKey);
    }

    /** 준비된 재료로 실제 이미지를 만든다. 20~25초가 걸린다. */
    private PetCharacterResponse produce(Prepared p) {
        String memberId = p.memberId;
        String petId = p.petId;
        Map<String, Object> pet = p.pet;
        byte[] source = p.source;
        String mimeType = p.mimeType;
        long used = p.used;

        // 1단계 — 사진에서 전신 캐릭터를 만든다. 배경은 초록 단색으로 받는다.
        byte[] fullbodyRaw = geminiImageClient.generate(source, mimeType, prompt(true));
        failIfInterrupted();
        if (fullbodyRaw == null) {
            throw new BusinessException("캐릭터를 만들지 못했어요. 다른 사진으로 다시 시도해 주세요.");
        }

        // 2단계 — 만들어진 캐릭터를 입력으로 넣어야 같은 아이의 얼굴이 나온다.
        // 배경을 빼기 전 원본을 넣는다. 투명 PNG를 입력하면 모델이 투명 영역을 검게
        // 받아들여 엉뚱한 색 배경을 만들고, 그러면 크로마키가 걸리지 않는다.
        byte[] profileRaw = geminiImageClient.generate(fullbodyRaw, "image/png", prompt(false));
        failIfInterrupted();
        if (profileRaw == null) {
            // 얼굴 생성만 실패하면 전신 이미지라도 남긴다.
            log.warn("[PET_CHARACTER_PROFILE_FAILED] 프로필 생성 실패 - petId: {}", petId);
        }

        String characterKey = null;
        String profileKey = null;
        try {
            characterKey = store(chromaKeyRemover.removeGreenBackground(fullbodyRaw));
            if (profileRaw != null) {
                profileKey = store(chromaKeyRemover.removeGreenBackground(profileRaw));
            }

            // UPDATE 한 문장이라 별도 트랜잭션이 필요 없다. 같은 클래스 안에서 @Transactional
            // 메서드를 직접 부르면 프록시를 거치지 않아 어차피 적용되지도 않는다.
            failIfInterrupted();
            if (petMapper.updateCharacterImages(petId, memberId, profileKey, characterKey) != 1) {
                throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
            }
        } catch (RuntimeException e) {
            // 저장이나 DB 갱신이 실패하면 방금 만든 파일은 아무도 참조하지 않는다.
            deleteQuietly(characterKey);
            deleteQuietly(profileKey);
            throw e;
        }

        // 갱신이 끝난 뒤에 지운다. 먼저 지우면 갱신 실패 시 이전 이미지까지 잃는다.
        deleteQuietly(text(pet, "profile_img", "profileImg"));
        deleteQuietly(text(pet, "character_img", "characterImg"));

        return PetCharacterResponse.builder()
                .petId(petId)
                .profileImg(fileStorage.signedUrl(profileKey))
                .characterImg(fileStorage.signedUrl(characterKey))
                .remainingToday((int) Math.max(0, dailyLimit - used))
                .build();
    }

    /** 외부 클라이언트가 인터럽트를 예외로 바꾸지 않고 반환해도 종료 요청을 놓치지 않는다. */
    private void failIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new GenerationInterruptedException();
        }
    }

    private String quotaKey(String memberId) {
        return RATE_LIMIT_PREFIX + memberId + ":" + LocalDate.now();
    }

    private long consumeDailyQuota(String key) {
        long count = rateLimiter.incrementWithExpiry(key, secondsUntilMidnight());
        if (count > dailyLimit) {
            throw BusinessException.conflict(
                    "오늘은 캐릭터를 " + dailyLimit + "번까지 만들 수 있어요. 내일 다시 시도해 주세요.");
        }
        return count;
    }

    /** 자정에 카운터가 초기화되도록 남은 시간을 TTL로 준다. */
    private long secondsUntilMidnight() {
        LocalDateTime now = LocalDateTime.now();
        return Math.max(60, Duration.between(now, LocalDate.now().plusDays(1).atStartOfDay()).getSeconds());
    }

    private void validatePhoto(MultipartFile photo) {
        if (photo == null || photo.isEmpty()) {
            throw new BusinessException("반려동물 사진을 첨부해 주세요.");
        }
        if (photo.getSize() > MAX_PHOTO_BYTES) {
            throw new BusinessException("사진은 10MB까지 올릴 수 있어요.");
        }
        String contentType = photo.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new BusinessException("PNG, JPG, WEBP 이미지만 올릴 수 있어요.");
        }
    }

    /** 프롬프트는 리소스 파일로 둬서 문구를 고칠 때 코드를 건드리지 않게 한다. */
    private String prompt(boolean fullbody) {
        if (fullbody) {
            if (fullbodyPrompt == null) {
                fullbodyPrompt = readPrompt("prompts/pet-character-fullbody.txt");
            }
            return fullbodyPrompt;
        }
        if (profilePrompt == null) {
            profilePrompt = readPrompt("prompts/pet-character-profile.txt");
        }
        return profilePrompt;
    }

    private String readPrompt(String path) {
        try {
            byte[] bytes = FileCopyUtils.copyToByteArray(new ClassPathResource(path).getInputStream());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 파일을 읽지 못했습니다: " + path, e);
        }
    }

    /** DB에는 저장 키만 넣는다. 화면에 보여줄 주소는 응답을 만들 때 붙인다. */
    private String store(byte[] image) {
        return fileStorage.store(image, UPLOAD_SUB_DIR, "png");
    }

    /** 파일 정리는 부가 작업이라 실패해도 본 흐름을 막지 않는다. */
    private void deleteQuietly(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            fileStorage.delete(path);
        } catch (RuntimeException e) {
            log.warn("[PET_CHARACTER_CLEANUP_FAILED] 이미지 정리 실패 - path: {}", path, e);
        }
    }

    private static String text(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                Object value = map.get(key);
                return value == null ? null : String.valueOf(value);
            }
        }
        return null;
    }

    private byte[] readBytes(MultipartFile photo) {
        try {
            return photo.getBytes();
        } catch (IOException e) {
            throw new BusinessException("사진을 읽지 못했어요. 다시 시도해 주세요.");
        }
    }

    private void requireMemberId(String memberId) {
        if (memberId == null || memberId.isBlank()) {
            throw BusinessException.unauthorized("로그인이 필요합니다.");
        }
    }

    /** 요청 스레드에서 준비를 마친 재료. 백그라운드로 그대로 넘어간다. */
    private static class Prepared {
        final String memberId;
        final String petId;
        final Map<String, Object> pet;
        final byte[] source;
        final String mimeType;
        final long used;
        final String quotaKey;

        Prepared(String memberId, String petId, Map<String, Object> pet,
                 byte[] source, String mimeType, long used, String quotaKey) {
            this.memberId = memberId;
            this.petId = petId;
            this.pet = pet;
            this.source = source;
            this.mimeType = mimeType;
            this.used = used;
            this.quotaKey = quotaKey;
        }
    }

    private static final class GenerationInterruptedException extends RuntimeException {
        private GenerationInterruptedException() {
            super("캐릭터 생성 스레드가 종료됐습니다.");
        }
    }
}
