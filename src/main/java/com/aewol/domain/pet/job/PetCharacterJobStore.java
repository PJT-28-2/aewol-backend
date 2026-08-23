package com.aewol.domain.pet.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 캐릭터 생성 작업 상태 저장소.
 *
 * <p>Redis에 두는 이유는 인스턴스가 둘 이상일 때다. 작업을 처리한 인스턴스와 상태를 물어본
 * 요청이 받은 인스턴스가 다를 수 있으므로, 메모리에 들고 있으면 방금 만든 캐릭터를
 * "없는 작업"이라고 답하게 된다.
 *
 * <p>TTL을 30분으로 둔다. 생성이 20~25초 걸리고 화면은 그 자리에서 결과를 받아 가므로
 * 그보다 훨씬 길면 충분하다. 사용자가 창을 닫았다 돌아오는 경우까지 감안한 여유다.
 */
@Slf4j
@Component
public class PetCharacterJobStore {

    private static final String KEY_PREFIX = "petCharacter:job:";
    private static final long TTL_MINUTES = 30;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PetCharacterJobStore(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 작업 상태를 저장한다.
     *
     * @throws IllegalStateException 저장에 실패한 경우 — 상태를 남기지 못하면 사용자가
     *         결과를 영영 확인할 수 없으므로 조용히 넘기지 않는다
     */
    public void save(PetCharacterJob job) {
        try {
            redisTemplate.opsForValue().set(
                    KEY_PREFIX + job.getJobId(),
                    objectMapper.writeValueAsString(job),
                    TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new IllegalStateException("캐릭터 생성 작업 상태를 저장하지 못했습니다.", e);
        }
    }

    /** @return 없거나 읽을 수 없으면 {@code null} */
    public PetCharacterJob find(String jobId) {
        try {
            String cached = redisTemplate.opsForValue().get(KEY_PREFIX + jobId);
            if (cached == null) {
                return null;
            }
            return objectMapper.readValue(cached, PetCharacterJob.class);
        } catch (Exception e) {
            // 만료됐거나 형식이 깨진 경우다. 없는 작업으로 취급한다.
            log.warn("[PET_CHARACTER_JOB_READ_FAILED] 작업 상태를 읽지 못했습니다. jobId={}", jobId, e);
            return null;
        }
    }
}
