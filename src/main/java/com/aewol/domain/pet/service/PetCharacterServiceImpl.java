package com.aewol.domain.pet.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.ChromaKeyRemover;
import com.aewol.common.storage.FileStorage;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.pet.dto.PetCharacterResponse;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.external.gemini.GeminiImageClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
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

    private final GeminiImageClient geminiImageClient;
    private final ChromaKeyRemover chromaKeyRemover;
    private final FileStorage fileStorage;
    private final PetMapper petMapper;
    private final RedisRateLimiter rateLimiter;

    /** 호출마다 실제 비용이 나가므로 1인당 하루 횟수를 제한한다. */
    @Value("${external.gemini.image-daily-limit:5}")
    private int dailyLimit;

    private String fullbodyPrompt;
    private String profilePrompt;

    @Override
    public PetCharacterResponse generate(String memberId, String petId, MultipartFile photo) {
        requireMemberId(memberId);
        Map<String, Object> pet = petMapper.findByIdAndMemberId(petId, memberId);
        if (pet == null) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
        validatePhoto(photo);
        if (!geminiImageClient.isConfigured()) {
            throw new BusinessException("AI 이미지 생성이 아직 준비되지 않았어요.");
        }

        long used = consumeDailyQuota(memberId);

        byte[] source = readBytes(photo);
        String mimeType = photo.getContentType();

        // 1단계 — 사진에서 전신 캐릭터를 만든다. 배경은 초록 단색으로 받는다.
        byte[] fullbodyRaw = geminiImageClient.generate(source, mimeType, prompt(true));
        if (fullbodyRaw == null) {
            throw new BusinessException("캐릭터를 만들지 못했어요. 다른 사진으로 다시 시도해 주세요.");
        }

        // 2단계 — 만들어진 캐릭터를 입력으로 넣어야 같은 아이의 얼굴이 나온다.
        // 배경을 빼기 전 원본을 넣는다. 투명 PNG를 입력하면 모델이 투명 영역을 검게
        // 받아들여 엉뚱한 색 배경을 만들고, 그러면 크로마키가 걸리지 않는다.
        byte[] profileRaw = geminiImageClient.generate(fullbodyRaw, "image/png", prompt(false));
        if (profileRaw == null) {
            // 얼굴 생성만 실패하면 전신 이미지라도 남긴다.
            log.warn("[PET_CHARACTER_PROFILE_FAILED] 프로필 생성 실패 - petId: {}", petId);
        }

        String characterKey = store(chromaKeyRemover.removeGreenBackground(fullbodyRaw));
        String profileKey = profileRaw == null
                ? null
                : store(chromaKeyRemover.removeGreenBackground(profileRaw));

        // UPDATE 한 문장이라 별도 트랜잭션이 필요 없다. 같은 클래스 안에서 @Transactional
        // 메서드를 직접 부르면 프록시를 거치지 않아 어차피 적용되지도 않는다.
        if (petMapper.updateCharacterImages(petId, memberId, profileKey, characterKey) != 1) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }

        // 새 이미지를 DB에 반영한 뒤에 지운다. 먼저 지우면 갱신이 실패했을 때
        // 이전 이미지까지 잃는다.
        deleteIfPresent(pet, "profile_img", "profileImg");
        deleteIfPresent(pet, "character_img", "characterImg");

        return PetCharacterResponse.builder()
                .petId(petId)
                .profileImg(fileStorage.signedUrl(profileKey))
                .characterImg(fileStorage.signedUrl(characterKey))
                .remainingToday((int) Math.max(0, dailyLimit - used))
                .build();
    }

    private long consumeDailyQuota(String memberId) {
        String key = RATE_LIMIT_PREFIX + memberId + ":" + LocalDate.now();
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

    /** 재생성 전에 쓰던 이미지를 지운다. 놔두면 호출할 때마다 고아 파일이 쌓인다. */
    private void deleteIfPresent(Map<String, Object> pet, String... keys) {
        Object previous = value(pet, keys);
        if (previous != null && !String.valueOf(previous).isBlank()) {
            fileStorage.delete(String.valueOf(previous));
        }
    }

    private static Object value(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) return map.get(key);
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
}
