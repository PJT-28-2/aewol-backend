package com.aewol.domain.share.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.storage.FileStorage;
import com.aewol.domain.activity.mapper.ActivityLogMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.share.dto.CareDiaryResponse;
import com.aewol.domain.share.dto.CareDiaryUpdateRequest;
import com.aewol.domain.share.mapper.CareDiaryMapper;
import com.aewol.domain.share.mapper.ShareMapper;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class CareDiaryServiceImpl implements CareDiaryService {

    private static final int MAX_CONTENT_LENGTH = 500;
    private static final String UPLOAD_SUB_DIR = "diary";
    /** 업로드 경로로 공개되므로 스크립트가 실행될 수 있는 형식(svg, html 등)은 받지 않는다. */
    private static final Map<String, String> ALLOWED_IMAGE_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/jpg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private final CareDiaryMapper careDiaryMapper;
    private final ShareMapper shareMapper;
    private final PetMapper petMapper;
    private final ActivityLogMapper activityLogMapper;
    private final FileStorage fileStorage;

    @Override
    @Transactional
    public CareDiaryResponse create(String memberId, String petId, String diaryDate,
                                    String content, MultipartFile image) {
        memberId = requireMemberId(memberId);
        Map<String, Object> pet = assertCanAccess(memberId, petId);

        LocalDate date = parseDate(diaryDate);
        String normalizedContent = normalizeContent(content);
        boolean hasImage = image != null && !image.isEmpty();
        if (normalizedContent == null && !hasImage) {
            throw new BusinessException("사진이나 내용 중 하나는 입력해 주세요.");
        }

        String storedImageKey = null;
        try {
            Map<String, Object> diary = new HashMap<>();
            diary.put("petId", petId);
            diary.put("authorMemberId", memberId);
            diary.put("diaryDate", date.toString());
            diary.put("content", normalizedContent);
            careDiaryMapper.insert(diary);
            // diary_id는 AUTO_INCREMENT — useGeneratedKeys가 파라미터 맵에 채워준다
            String diaryId = text(diary, "diaryId");

            if (hasImage) {
                storedImageKey = storeImage(image);
                arrangeRollbackCleanup(storedImageKey);
                Map<String, Object> imageRow = new HashMap<>();
                imageRow.put("diaryId", diaryId);
                imageRow.put("imageUrl", storedImageKey);
                imageRow.put("sortOrder", 0);
                careDiaryMapper.insertImage(imageRow);
            }

            writeActivityLog(pet, petId, diaryId, memberId, date);
            return getDetail(memberId, diaryId);
        } catch (RuntimeException e) {
            // 테스트처럼 트랜잭션 동기화가 없는 호출과 메서드 내부 실패도 즉시 정리한다.
            deleteQuietly(storedImageKey);
            throw e;
        }
    }

    @Override
    public List<CareDiaryResponse> getMonthly(String memberId, String petId, String yearMonth) {
        memberId = requireMemberId(memberId);
        Map<String, Object> pet = assertCanAccess(memberId, petId);

        List<Map<String, Object>> rows =
                careDiaryMapper.findByPetIdAndMonth(petId, normalizeYearMonth(yearMonth));
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<String, List<String>> imagesByDiaryId = loadImages(rows.stream()
                .map(row -> text(row, "diaryId"))
                .collect(Collectors.toList()));

        String ownerId = text(pet, "member_id", "memberId");
        String requesterId = memberId;
        return rows.stream()
                .map(row -> toResponse(row, imagesByDiaryId, requesterId, ownerId))
                .collect(Collectors.toList());
    }

    @Override
    public CareDiaryResponse getDetail(String memberId, String diaryId) {
        memberId = requireMemberId(memberId);
        Map<String, Object> row = findDiary(diaryId);
        String petId = text(row, "petId");
        Map<String, Object> pet = assertCanAccess(memberId, petId);

        Map<String, List<String>> images = loadImages(List.of(diaryId));
        return toResponse(row, images, memberId, text(pet, "member_id", "memberId"));
    }

    @Override
    @Transactional
    public CareDiaryResponse update(String memberId, String diaryId, CareDiaryUpdateRequest request) {
        memberId = requireMemberId(memberId);
        Map<String, Object> row = findDiary(diaryId);
        assertCanAccess(memberId, text(row, "petId"));

        if (!memberId.equals(text(row, "authorMemberId"))) {
            throw BusinessException.forbidden("작성자만 일기를 수정할 수 있습니다.");
        }

        String diaryDate = request.getDiaryDate() == null || request.getDiaryDate().isBlank()
                ? dateText(value(row, "diaryDate"))
                : parseDate(request.getDiaryDate()).toString();
        String content = normalizeContent(request.getContent());
        if (content == null && loadImages(List.of(diaryId)).isEmpty()) {
            throw new BusinessException("사진이 없는 일기는 내용을 비울 수 없습니다.");
        }

        // version을 보낸 요청은 그 사이 다른 곳에서 저장됐는지까지 판정한다. 위에서 행을
        // 이미 읽어 존재는 확인했으므로, 여기서 0행이면 원인은 버전 불일치다.
        Long expectedVersion = request.getVersion();
        if (careDiaryMapper.update(diaryId, diaryDate, content, expectedVersion) != 1) {
            if (expectedVersion != null) {
                throw BusinessException.conflict(
                        "다른 곳에서 이 일기를 먼저 수정했어요. 최신 내용을 불러온 뒤 다시 저장해 주세요.");
            }
            throw BusinessException.notFound("수정할 일기를 찾을 수 없습니다.");
        }
        return getDetail(memberId, diaryId);
    }

    @Override
    @Transactional
    public void delete(String memberId, String diaryId) {
        memberId = requireMemberId(memberId);
        Map<String, Object> row = findDiary(diaryId);
        Map<String, Object> pet = assertCanAccess(memberId, text(row, "petId"));

        boolean isAuthor = memberId.equals(text(row, "authorMemberId"));
        boolean isOwner = memberId.equals(text(pet, "member_id", "memberId"));
        if (!isAuthor && !isOwner) {
            throw BusinessException.forbidden("작성자 또는 대표 보호자만 일기를 삭제할 수 있습니다.");
        }

        List<String> imageKeys = careDiaryMapper.findImagesByDiaryIds(List.of(diaryId)).stream()
                .map(image -> text(image, "imageUrl"))
                .filter(key -> key != null && !key.isBlank())
                .collect(Collectors.toList());
        if (careDiaryMapper.softDelete(diaryId) != 1) {
            throw BusinessException.notFound("삭제할 일기를 찾을 수 없습니다.");
        }
        arrangeCommittedCleanup(imageKeys);
    }

    /**
     * 반려동물 소유자이거나 초대를 수락한 공동육아 구성원이면 통과한다.
     * 일기는 참여를 늘리기 위한 기능이라 VIEWER도 작성할 수 있게 조회와 같은 기준을 쓴다.
     */
    private Map<String, Object> assertCanAccess(String memberId, String petId) {
        Map<String, Object> pet = petMapper.findById(petId);
        if (pet == null) throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        if (!memberId.equals(text(pet, "member_id", "memberId"))
                && shareMapper.findAcceptedAccess(petId, memberId) == null) {
            throw BusinessException.forbidden("공동육아 일기를 볼 권한이 없습니다.");
        }
        return pet;
    }

    private Map<String, Object> findDiary(String diaryId) {
        if (diaryId == null || diaryId.isBlank()) {
            throw new BusinessException("일기를 선택해 주세요.");
        }
        Map<String, Object> row = careDiaryMapper.findById(diaryId);
        if (row == null) throw BusinessException.notFound("일기를 찾을 수 없습니다.");
        return row;
    }

    /**
     * 업로드한 파일이 실제 이미지인지 확인한 뒤 저장한다.
     *
     * <p>원본 확장자를 그대로 쓰면 {@code .svg}나 {@code .html}이 업로드 경로로 공개돼
     * 스크립트가 실행될 수 있다. 그래서 (1) MIME 타입을 허용 목록으로 제한하고,
     * (2) 파일 앞머리 시그니처로 실제 이미지인지 확인하고, (3) 확장자는 파일명이 아니라
     * 검증된 타입에서 결정한다.
     *
     * <p>시그니처로 확인하는 이유는 {@code ImageIO}가 WEBP 디코더를 기본 제공하지 않아
     * 정상 파일까지 거부하기 때문이다.
     *
     * <p>DB에는 저장 키만 넣는다. 화면에 보여줄 주소는 조회 시점에 만든다.
     */
    private String storeImage(MultipartFile image) {
        String contentType = image.getContentType();
        String declaredExtension = contentType == null
                ? null
                : ALLOWED_IMAGE_TYPES.get(contentType.toLowerCase(Locale.ROOT));
        if (declaredExtension == null) {
            throw new BusinessException("JPG, PNG, WEBP 이미지만 올릴 수 있어요.");
        }

        try {
            byte[] bytes = image.getBytes();
            String actualExtension = detectImageExtension(bytes);
            if (actualExtension == null) {
                throw new BusinessException("이미지 파일이 아니거나 손상된 파일이에요.");
            }
            // 확장자는 선언된 MIME이 아니라 실제 내용에서 판별한 값을 쓴다.
            return fileStorage.store(bytes, UPLOAD_SUB_DIR, actualExtension);
        } catch (IOException e) {
            log.error("[CARE_DIARY_UPLOAD_FAILED] 일기 이미지 저장 실패 - size: {}", image.getSize(), e);
            throw new BusinessException("사진 저장에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /** DB 커밋이 실패하면 저장 키를 참조할 행이 없으므로 새 파일을 지운다. */
    private void arrangeRollbackCleanup(String key) {
        if (key == null || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) deleteQuietly(key);
            }
        });
    }

    /** 삭제 트랜잭션이 확정된 뒤에만 실제 파일을 제거한다. */
    private void arrangeCommittedCleanup(List<String> keys) {
        if (keys.isEmpty()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    keys.forEach(CareDiaryServiceImpl.this::deleteQuietly);
                }
            });
        } else {
            keys.forEach(this::deleteQuietly);
        }
    }

    private void deleteQuietly(String key) {
        if (key == null || key.isBlank()) return;
        try {
            fileStorage.delete(key);
        } catch (RuntimeException e) {
            log.warn("[CARE_DIARY_FILE_CLEANUP_FAILED] 이미지 정리 실패 - key: {}", key, e);
        }
    }

    /** 파일 앞머리 시그니처로 이미지 형식을 판별한다. 아니면 null. */
    private static String detectImageExtension(byte[] bytes) {
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && (bytes[4] & 0xFF) == 0x0D && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A && (bytes[7] & 0xFF) == 0x0A) {
            return "png";
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "webp";
        }
        return null;
    }

    /**
     * 일기를 남기면 기존 공동육아 활동 내역(GET /api/share/logs)에도 함께 노출한다.
     * activity_log.wallet_id는 NOT NULL이라 소유자의 대표 지갑을 찾아 붙인다.
     * 로그 적재가 실패해도 일기 작성 자체는 성공시킨다.
     */
    private void writeActivityLog(Map<String, Object> pet, String petId, String diaryId,
                                  String memberId, LocalDate date) {
        try {
            Map<String, Object> wallet =
                    shareMapper.findMainWalletByMemberId(text(pet, "member_id", "memberId"));
            if (wallet == null) {
                log.warn("[CARE_DIARY_LOG_SKIPPED] 소유자 지갑을 찾지 못해 활동 로그를 남기지 않는다 - petId: {}", petId);
                return;
            }
            Map<String, Object> logRow = new HashMap<>();
            logRow.put("walletId", text(wallet, "wallet_id", "walletId"));
            logRow.put("petId", petId);
            logRow.put("actionType", "CREATE");
            logRow.put("targetType", "DIARY");
            logRow.put("targetId", diaryId);
            logRow.put("title", "공동육아 일기가 등록되었어요");
            logRow.put("description", date + " 기록");
            activityLogMapper.insert(logRow);
        } catch (RuntimeException e) {
            log.warn("[CARE_DIARY_LOG_FAILED] 활동 로그 적재 실패 - diaryId: {}", diaryId, e);
        }
    }

    private Map<String, List<String>> loadImages(List<String> diaryIds) {
        if (diaryIds.isEmpty()) return Map.of();
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : careDiaryMapper.findImagesByDiaryIds(diaryIds)) {
            grouped.computeIfAbsent(text(row, "diaryId"), key -> new ArrayList<>())
                    .add(text(row, "imageUrl"));
        }
        return grouped;
    }

    private CareDiaryResponse toResponse(Map<String, Object> row,
                                         Map<String, List<String>> imagesByDiaryId,
                                         String requesterId,
                                         String ownerId) {
        String diaryId = text(row, "diaryId");
        String authorId = text(row, "authorMemberId");
        boolean isAuthor = requesterId.equals(authorId);
        return CareDiaryResponse.builder()
                .id(diaryId)
                .petId(text(row, "petId"))
                .diaryDate(dateText(value(row, "diaryDate")))
                .content(text(row, "content"))
                .images(imagesByDiaryId.getOrDefault(diaryId, List.of()).stream()
                        .map(fileStorage::signedUrl)
                        .collect(Collectors.toList()))
                .authorId(authorId)
                .authorName(text(row, "authorName"))
                .createdAt(dateTimeText(value(row, "createdAt")))
                .version(longValue(row, "version"))
                .editable(isAuthor)
                .deletable(isAuthor || requesterId.equals(ownerId))
                .build();
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("날짜를 선택해 주세요.");
        }
        try {
            LocalDate date = LocalDate.parse(value.trim());
            if (date.isAfter(LocalDate.now())) {
                throw new BusinessException("아직 오지 않은 날짜에는 기록할 수 없습니다.");
            }
            return date;
        } catch (DateTimeParseException e) {
            throw new BusinessException("날짜 형식이 올바르지 않습니다. (yyyy-MM-dd)");
        }
    }

    /** 값이 없으면 이번 달을 쓴다. */
    private String normalizeYearMonth(String yearMonth) {
        if (yearMonth == null || yearMonth.isBlank()) {
            return YearMonth.now().toString();
        }
        try {
            return YearMonth.parse(yearMonth.trim()).toString();
        } catch (DateTimeParseException e) {
            throw new BusinessException("조회할 월 형식이 올바르지 않습니다. (yyyy-MM)");
        }
    }

    private String normalizeContent(String content) {
        if (content == null) return null;
        String trimmed = content.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException("내용은 " + MAX_CONTENT_LENGTH + "자까지 입력할 수 있습니다.");
        }
        return trimmed;
    }

    private String requireMemberId(String memberId) {
        if (memberId == null || memberId.isBlank()) throw BusinessException.unauthorized("로그인이 필요합니다.");
        return memberId;
    }

    private static Object value(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }

    private static String text(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        return value == null ? null : String.valueOf(value);
    }

    /** MyBatis가 map으로 돌려주는 수치형은 드라이버에 따라 Long/BigInteger 등으로 달라진다. */
    private static Long longValue(Map<String, Object> map, String key) {
        Object raw = map == null ? null : map.get(key);
        if (raw == null) {
            return null;
        }
        return raw instanceof Number number ? number.longValue() : Long.valueOf(raw.toString());
    }

    private static String dateText(Object value) {
        if (value instanceof LocalDate) return value.toString();
        if (value instanceof java.sql.Date) return ((java.sql.Date) value).toLocalDate().toString();
        return value == null ? null : String.valueOf(value);
    }

    private static String dateTimeText(Object value) {
        if (value instanceof LocalDateTime) return value.toString();
        if (value instanceof Timestamp) return ((Timestamp) value).toLocalDateTime().toString();
        return value == null ? null : String.valueOf(value);
    }
}
