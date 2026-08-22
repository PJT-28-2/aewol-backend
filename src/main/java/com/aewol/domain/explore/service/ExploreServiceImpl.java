package com.aewol.domain.explore.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.storage.FileStorage;
import com.aewol.domain.explore.dto.ExplorePageResponse;
import com.aewol.domain.explore.dto.ExplorePostResponse;
import com.aewol.domain.explore.dto.PetPublicProfileResponse;
import com.aewol.domain.explore.mapper.ExploreMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExploreServiceImpl implements ExploreService {

    private static final int DEFAULT_SIZE = 21;
    private static final int MAX_SIZE = 60;
    /** 커서는 "정렬 키|id" 한 문자열로 주고받는다. 화면이 두 값을 따로 들고 다닐 필요가 없다. */
    private static final String CURSOR_SEPARATOR = "|";
    private static final DateTimeFormatter CURSOR_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ExploreMapper exploreMapper;
    private final FileStorage fileStorage;

    @Override
    @Transactional(readOnly = true)
    public ExplorePageResponse getExploreFeed(String cursor, int size) {
        int limit = normalizeSize(size);
        String[] parsed = parseCursor(cursor);
        return toPage(exploreMapper.findPublicPosts(parsed[0], parsed[1], limit + 1), limit);
    }

    @Override
    @Transactional(readOnly = true)
    public ExplorePageResponse getPetPosts(String petId, String cursor, int size) {
        int limit = normalizeSize(size);
        String[] parsed = parseCursor(cursor);
        return toPage(exploreMapper.findPublicPostsByPet(petId, parsed[0], parsed[1], limit + 1), limit);
    }

    @Override
    @Transactional(readOnly = true)
    public PetPublicProfileResponse getPetProfile(String petId) {
        Map<String, Object> row = exploreMapper.findPublicProfile(petId);
        if (row == null) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
        // AI 캐릭터를 먼저 쓴다. 실사진을 올리지 않고도 계정이 성립하게 하려는 것이다.
        String image = text(row, "characterImg");
        if (image == null) {
            image = text(row, "profileImg");
        }
        return PetPublicProfileResponse.builder()
                .petId(text(row, "petId"))
                .name(text(row, "name"))
                .species(text(row, "species"))
                .breed(text(row, "breed"))
                .profileImage(image == null ? null : fileStorage.signedUrl(image))
                .instagramId(text(row, "instagramId"))
                .postCount(intValue(row.get("postCount")))
                .build();
    }

    /**
     * limit + 1건을 조회해 다음 장이 있는지 본다. 초과분은 잘라내고 마지막 행으로 커서를 만든다.
     */
    private ExplorePageResponse toPage(List<Map<String, Object>> rows, int limit) {
        boolean hasNext = rows.size() > limit;
        List<Map<String, Object>> pageRows = hasNext ? rows.subList(0, limit) : rows;
        Map<String, List<String>> imagesByDiaryId = loadImages(pageRows);

        List<ExplorePostResponse> posts = pageRows.stream()
                .map(row -> toPost(row, imagesByDiaryId))
                .collect(Collectors.toList());

        String nextCursor = null;
        if (hasNext && !pageRows.isEmpty()) {
            Map<String, Object> last = pageRows.get(pageRows.size() - 1);
            nextCursor = dateTimeText(last.get("createdAt")) + CURSOR_SEPARATOR + text(last, "diaryId");
        }
        return ExplorePageResponse.builder().posts(posts).nextCursor(nextCursor).build();
    }

    private ExplorePostResponse toPost(Map<String, Object> row, Map<String, List<String>> imagesByDiaryId) {
        String diaryId = text(row, "diaryId");
        List<String> images = imagesByDiaryId.getOrDefault(diaryId, List.of());
        return ExplorePostResponse.builder()
                .diaryId(diaryId)
                .petId(text(row, "petId"))
                .petName(text(row, "petName"))
                .imageUrl(images.isEmpty() ? null : images.get(0))
                .content(text(row, "content"))
                .diaryDate(dateText(row.get("diaryDate")))
                .createdAt(dateTimeText(row.get("createdAt")))
                .build();
    }

    /** 일기마다 이미지를 다시 조회하지 않고 한 번에 묶어 온다. */
    private Map<String, List<String>> loadImages(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<String> diaryIds = rows.stream().map(row -> text(row, "diaryId")).collect(Collectors.toList());
        Map<String, List<String>> grouped = new HashMap<>();
        for (Map<String, Object> row : exploreMapper.findImagesByDiaryIds(diaryIds)) {
            // 공개 사본이 있으면 만료 없는 CDN 주소를 쓴다. 서명 URL은 피드에서 곧 깨진다.
            // 사본이 아직 없는 사진은 아예 내보내지 않는다 — 깨진 이미지를 보여주느니
            // 그 글을 그리드에서 빼는 편이 낫다.
            String publicKey = text(row, "publicImageKey");
            if (publicKey == null) {
                continue;
            }
            String url = fileStorage.publicUrl(publicKey);
            if (url != null) {
                grouped.computeIfAbsent(text(row, "diaryId"), key -> new ArrayList<>()).add(url);
            }
        }
        return grouped;
    }

    private static int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    /** 형식이 어긋난 커서는 무시하고 첫 장을 준다. 400으로 막을 만큼 중요한 값이 아니다. */
    private static String[] parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new String[] {null, null};
        }
        int separator = cursor.lastIndexOf(CURSOR_SEPARATOR);
        if (separator <= 0 || separator == cursor.length() - 1) {
            return new String[] {null, null};
        }
        return new String[] {cursor.substring(0, separator), cursor.substring(separator + 1)};
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String dateText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        return value instanceof LocalDate date ? date.toString() : String.valueOf(value);
    }

    private static String dateTimeText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(CURSOR_FORMAT);
        }
        return value instanceof LocalDateTime dateTime
                ? dateTime.format(CURSOR_FORMAT)
                : String.valueOf(value);
    }
}
