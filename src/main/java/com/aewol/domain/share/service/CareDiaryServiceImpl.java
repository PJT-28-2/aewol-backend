package com.aewol.domain.share.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.storage.FileStorage;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.activity.mapper.ActivityLogMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.share.dto.AdminDiaryReportDetailResponse;
import com.aewol.domain.share.dto.AdminDiaryReportListItemResponse;
import com.aewol.domain.share.dto.AdminDiaryReportListResponse;
import com.aewol.domain.share.dto.AdminDiaryReportResolutionRequest;
import com.aewol.domain.share.dto.CareDiaryReportRequest;
import com.aewol.domain.share.dto.CareDiaryReportResponse;
import com.aewol.domain.inquiry.mapper.InquiryMapper;
import java.time.format.DateTimeFormatter;
import com.aewol.domain.share.dto.CareDiaryResponse;
import com.aewol.domain.share.dto.CareDiaryUpdateRequest;
import com.aewol.domain.share.dto.CareDiaryVisibilityRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class CareDiaryServiceImpl implements CareDiaryService {

    private static final String PUBLIC = "PUBLIC";
    private static final int MAX_CONTENT_LENGTH = 500;
    private static final String UPLOAD_SUB_DIR = "diary";
    /** 서로 다른 신고가 이 수에 닿아야 글을 내린다. 1건으로 내리면 오탐에도 바로 사라진다. */
    private static final int REPORT_HIDE_THRESHOLD = 3;
    private static final int REPORT_RATE_LIMIT_MAX = 5;
    private static final long REPORT_RATE_LIMIT_WINDOW_SECONDS = 15 * 60;
    private static final String REPORT_RATE_LIMIT_KEY_PREFIX = "rate:diary-report:";
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
    private final InquiryMapper inquiryMapper;
    private final RedisRateLimiter redisRateLimiter;

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

    /**
     * 공개 여부 전환. 올리는 권한과 내리는 권한이 다르다.
     *
     * <p>일기는 공동 작성이라 여러 가족이 한 반려동물의 일기에 쓴다. 대표 보호자가 남이 쓴
     * 글을 마음대로 공개하면 "내가 쓴 글인데 내 통제 밖에서 공개됐다"가 된다. 그래서 공개는
     * 작성자만 하고, 내리는 것은 작성자와 대표 보호자 둘 다 할 수 있게 한다.
     *
     * <p>신고로 내려간 글은 작성자도 다시 공개하지 못한다. 되돌리는 것은 관리자 몫이다.
     */
    @Override
    @Transactional
    public CareDiaryResponse changeVisibility(String memberId, String diaryId,
                                              CareDiaryVisibilityRequest request) {
        memberId = requireMemberId(memberId);
        Map<String, Object> row = findDiary(diaryId);
        String petId = text(row, "petId");
        Map<String, Object> pet = assertCanAccess(memberId, petId);

        String visibility = request.getVisibility();
        boolean isAuthor = memberId.equals(text(row, "authorMemberId"));
        // petMapper.findById는 SELECT * 라 컬럼명이 그대로 온다. assertCanAccess와 동일하게
        // 두 표기를 모두 받는다.
        boolean isPetOwner = memberId.equals(text(pet, "member_id", "memberId"));

        // 사진은 한 번만 읽는다. 공개 가능 여부를 가리는 데도, 사본을 맞추는 데도
        // 같은 목록이 필요하다.
        List<Map<String, Object>> images = careDiaryMapper.findImagesForPublish(diaryId);

        if (PUBLIC.equals(visibility)) {
            if (!isAuthor) {
                throw BusinessException.forbidden("일기를 공개하는 것은 작성자만 할 수 있습니다.");
            }
            if (value(row, "hiddenByReportAt") != null) {
                throw BusinessException.conflict(
                        "신고로 노출이 중단된 일기예요. 고객센터 확인 후에 다시 공개할 수 있어요.");
            }
            // 멍스타그램은 사진으로 훑어보는 화면이라, 사진 없는 글은 탐색 그리드와
            // 프로필 어디에도 자리가 없다. 공개는 됐는데 아무 데도 안 보이는 상태가
            // 되므로 애초에 막고 이유를 알린다.
            if (images.isEmpty()) {
                throw new BusinessException("사진이 있는 일기만 공개할 수 있어요. 사진을 추가한 뒤 다시 시도해 주세요.");
            }
        } else if (!isAuthor && !isPetOwner) {
            throw BusinessException.forbidden("작성자 또는 대표 보호자만 일기를 비공개로 바꿀 수 있습니다.");
        }

        if (PUBLIC.equals(visibility)) {
            // 사본을 못 만들었는데 PUBLIC이면 피드에 빈 칸이 생긴다. 사본이 준비된 뒤에만
            // 공개로 바꾼다. 실패하면 예외로 트랜잭션이 롤백된다.
            publishPublicImagesOrThrow(images);
        }
        if (careDiaryMapper.updateVisibility(diaryId, visibility) != 1) {
            throw BusinessException.notFound("공개 여부를 바꿀 일기를 찾을 수 없습니다.");
        }
        if (!PUBLIC.equals(visibility)) {
            syncPublicImages(images, false);
        }
        return getDetail(memberId, diaryId);
    }

    /**
     * 공개 전환에 맞춰 사진 사본을 맞춘다.
     *
     * <p>비공개로 되돌리면 CDN 사본을 지운다. 원본은 손대지 않는다. 사본 삭제가 실패해도
     * 예외를 던지지 않는다 — 노출을 멈추는 쪽이 먼저다.
     *
     * <p>공개로 올릴 때는 {@link #publishPublicImagesOrThrow}를 쓴다. 사본 없이 PUBLIC이 되면
     * 피드에 빈 칸이 생긴다.
     */
    private void syncPublicImages(List<Map<String, Object>> images, boolean publishing) {
        if (publishing) {
            publishPublicImagesOrThrow(images);
            return;
        }
        for (Map<String, Object> image : images) {
            String imageId = text(image, "imageId");
            String publicKey = text(image, "publicImageKey");
            if (publicKey != null) {
                fileStorage.unpublish(publicKey);
                careDiaryMapper.updatePublicImageKey(imageId, null);
            }
        }
    }

    /** 사본을 모두 만든 뒤에만 호출부가 PUBLIC으로 바꾼다. 하나라도 실패하면 공개하지 않는다. */
    private void publishPublicImagesOrThrow(List<Map<String, Object>> images) {
        publishMissingPublicCopiesOrThrow(
                images, "사진을 공개하지 못했어요. 잠시 후 다시 시도해 주세요.");
    }

    /**
     * 신고 접수. 서로 다른 신고가 임계치에 닿으면 노출을 멈추고 고객센터 문의로 잇는다.
     *
     * <p>1건으로 내리면 오탐에도 글이 바로 사라진다. 그 전까지는 접수만 하고, 같은 사람의
     * 반복 신고는 UNIQUE로 한 건으로 센다. 짧은 시간에 여러 글을 두드리는 것은 횟수로 막는다.
     *
     * <p>공개된 글만 신고 대상이다. 비공개 일기는 신고자가 볼 수 없고, 볼 수 있다면 이미
     * 그 가족이라 신고가 아니라 대화로 풀 일이다.
     */
    @Override
    @Transactional
    public CareDiaryReportResponse report(String memberId, String diaryId,
                                          CareDiaryReportRequest request) {
        memberId = requireMemberId(memberId);
        Map<String, Object> row = findDiary(diaryId);

        if (!PUBLIC.equals(text(row, "visibility"))) {
            throw BusinessException.notFound("신고할 수 있는 게시물이 아닙니다.");
        }
        // 자기 글은 신고가 아니라 비공개로 내리면 된다. 신고로 내리면 관리자만 되돌릴 수
        // 있어 스스로를 잠그는 셈이 된다.
        if (memberId.equals(text(row, "authorMemberId"))) {
            throw new BusinessException("자기가 쓴 글은 신고할 수 없어요. 비공개로 바꿔 주세요.");
        }

        String rateLimitKey = REPORT_RATE_LIMIT_KEY_PREFIX + memberId;
        long requestCount = redisRateLimiter.incrementWithExpiry(
                rateLimitKey, REPORT_RATE_LIMIT_WINDOW_SECONDS);
        if (requestCount > REPORT_RATE_LIMIT_MAX) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "신고 요청이 너무 많아요. 15분 후 다시 시도해 주세요.");
        }

        Map<String, Object> report = new HashMap<>();
        report.put("diaryId", diaryId);
        report.put("reporterId", memberId);
        report.put("reason", request.getReason());
        if (careDiaryMapper.insertReport(report) == 0) {
            redisRateLimiter.rollback(rateLimitKey);
            throw BusinessException.conflict("이미 신고한 게시물이에요. 처리 결과를 기다려 주세요.");
        }
        String reportId = String.valueOf(report.get("reportId"));

        boolean hidden = false;
        if (careDiaryMapper.countReportsByDiary(diaryId) >= REPORT_HIDE_THRESHOLD) {
            hidden = careDiaryMapper.hideByReport(diaryId) == 1;
            if (hidden) {
                // 피드에서 빼는 것만으로는 부족하다. CDN 주소를 이미 아는 사람에게는 사진이
                // 계속 보인다. 오탐이면 원본이 남아 있으니 사본을 다시 만들면 된다.
                syncPublicImages(careDiaryMapper.findImagesForPublish(diaryId), false);
            }
        }
        String inquiryNumber = createReportInquiry(memberId, diaryId, request.getReason(), reportId);

        return CareDiaryReportResponse.builder()
                .reportId(reportId)
                .inquiryNumber(inquiryNumber)
                .hidden(hidden)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public AdminDiaryReportListResponse getAdminReports(String status, int page, int size) {
        String normalizedStatus = normalizeReportStatus(status);
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 50));
        List<Map<String, Object>> rows = careDiaryMapper.findAdminReports(
                normalizedStatus, safeSize + 1, safePage * safeSize);
        boolean hasNext = rows.size() > safeSize;
        List<AdminDiaryReportListItemResponse> reports = rows.stream()
                .limit(safeSize)
                .map(this::toAdminReportListItem)
                .collect(Collectors.toList());
        return AdminDiaryReportListResponse.builder()
                .reports(reports)
                .page(safePage)
                .size(safeSize)
                .hasNext(hasNext)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public AdminDiaryReportDetailResponse getAdminReport(String reportId) {
        return toAdminReportDetail(findAdminReport(reportId));
    }

    @Override
    @Transactional
    public AdminDiaryReportDetailResponse resolveAdminReport(
            String adminId, String reportId, AdminDiaryReportResolutionRequest request) {
        adminId = requireMemberId(adminId);
        Map<String, Object> report = findAdminReport(reportId);
        if (!"PENDING".equals(text(report, "status"))) {
            throw BusinessException.conflict("이미 처리된 신고입니다.");
        }

        String diaryId = text(report, "diaryId");
        String resolution = request.getResolution();
        if ("RESTORE".equals(resolution)) {
            if (value(report, "deletedAt") != null) {
                throw BusinessException.conflict("이미 삭제된 게시물은 복원할 수 없습니다.");
            }
            restorePublicImages(careDiaryMapper.findImagesForPublish(diaryId));
            if (careDiaryMapper.restoreByReport(diaryId) != 1) {
                throw BusinessException.conflict("게시물을 복원할 수 없습니다.");
            }
        }

        String adminNote = normalizeAdminNote(request.getAdminNote());
        if (careDiaryMapper.resolvePendingReportsByDiary(
                diaryId, resolution, adminNote, adminId) == 0) {
            throw BusinessException.conflict("처리할 신고가 없습니다.");
        }
        String inquiryAnswer = adminNote != null ? adminNote : "신고가 처리되었습니다.";
        inquiryMapper.answerWaitingLinkedToDiary(diaryId, inquiryAnswer);
        return toAdminReportDetail(findAdminReport(reportId));
    }

    private Map<String, Object> findAdminReport(String reportId) {
        if (reportId == null || reportId.isBlank()) {
            throw BusinessException.notFound("신고를 찾을 수 없습니다.");
        }
        Map<String, Object> report = careDiaryMapper.findAdminReportById(reportId);
        if (report == null) throw BusinessException.notFound("신고를 찾을 수 없습니다.");
        return report;
    }

    private String normalizeReportStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!"PENDING".equals(normalized) && !"RESOLVED".equals(normalized)) {
            throw new BusinessException("신고 상태가 올바르지 않습니다.");
        }
        return normalized;
    }

    private String normalizeAdminNote(String adminNote) {
        if (adminNote == null || adminNote.isBlank()) return null;
        return adminNote.trim();
    }

    private void restorePublicImages(List<Map<String, Object>> images) {
        if (images.isEmpty()) {
            throw BusinessException.conflict("복원할 게시물 사진을 찾을 수 없습니다.");
        }
        publishMissingPublicCopiesOrThrow(
                images, "게시물 사진을 복원하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    /**
     * 없는 공개 사본만 만들고, 만든 키를 추적한다. 중간 실패나 이후 DB 롤백 때
     * 이미 만든 사본을 지우지 않으면 공개 URL을 가진 고아 파일이 남는다.
     */
    private void publishMissingPublicCopiesOrThrow(
            List<Map<String, Object>> images, String conflictMessage) {
        List<String> createdKeys = new ArrayList<>();
        try {
            for (Map<String, Object> image : images) {
                if (text(image, "publicImageKey") != null) {
                    continue;
                }
                String created = fileStorage.publish(text(image, "imageUrl"));
                if (created == null) {
                    throw BusinessException.conflict(conflictMessage);
                }
                createdKeys.add(created);
                careDiaryMapper.updatePublicImageKey(text(image, "imageId"), created);
            }
            arrangeRollbackPublicCleanup(createdKeys);
        } catch (RuntimeException e) {
            createdKeys.forEach(this::unpublishQuietly);
            throw e;
        }
    }

    private AdminDiaryReportListItemResponse toAdminReportListItem(Map<String, Object> row) {
        return AdminDiaryReportListItemResponse.builder()
                .reportId(text(row, "reportId"))
                .diaryId(text(row, "diaryId"))
                .reason(text(row, "reason"))
                .status(text(row, "status"))
                .resolution(text(row, "resolution"))
                .reporterName(text(row, "reporterName"))
                .authorName(text(row, "authorName"))
                .petName(text(row, "petName"))
                .contentPreview(text(row, "contentPreview"))
                .createdAt(dateTimeText(value(row, "createdAt")))
                .resolvedAt(dateTimeText(value(row, "resolvedAt")))
                .build();
    }

    private AdminDiaryReportDetailResponse toAdminReportDetail(Map<String, Object> row) {
        String diaryId = text(row, "diaryId");
        List<String> images = careDiaryMapper.findImagesForPublish(diaryId).stream()
                .map(image -> text(image, "imageUrl"))
                .filter(key -> key != null && !key.isBlank())
                .map(fileStorage::signedUrl)
                .collect(Collectors.toList());
        return AdminDiaryReportDetailResponse.builder()
                .reportId(text(row, "reportId"))
                .diaryId(diaryId)
                .reason(text(row, "reason"))
                .status(text(row, "status"))
                .resolution(text(row, "resolution"))
                .adminNote(text(row, "adminNote"))
                .reporterName(text(row, "reporterName"))
                .reporterEmail(text(row, "reporterEmail"))
                .authorName(text(row, "authorName"))
                .petName(text(row, "petName"))
                .content(text(row, "content"))
                .images(images)
                .inquiryNumber(text(row, "inquiryNumber"))
                .createdAt(dateTimeText(value(row, "createdAt")))
                .resolvedAt(dateTimeText(value(row, "resolvedAt")))
                .build();
    }

    /**
     * 신고를 고객센터 문의로 남긴다. 신고자가 자기 문의 내역에서 진행 상태를 볼 수 있게 된다.
     *
     * <p>사용자 입력을 받는 경로가 아니라 매퍼를 직접 쓴다. InquiryService의 카테고리 검증을
     * 타면 ALLOWED_CATEGORIES에 '신고'를 넣어야 하고, 그러면 프론트 카테고리 목록과도 다시
     * 맞춰야 한다. 내부 생성이라 그럴 이유가 없다.
     */
    private String createReportInquiry(String memberId, String diaryId, String reason, String reportId) {
        try {
            Map<String, Object> inquiry = new HashMap<>();
            inquiry.put("memberId", memberId);
            inquiry.put("category", "신고");
            inquiry.put("title", "게시물 신고 (일기 " + diaryId + ")");
            inquiry.put("content", "신고 사유: " + reason + "\n대상 일기 ID: " + diaryId);
            inquiry.put("replyEmail", null);
            inquiryMapper.insert(inquiry);

            String inquiryId = String.valueOf(inquiry.get("inquiryId"));
            String inquiryNumber = "AEW-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + "-" + String.format("%04d", Long.parseLong(inquiryId));
            inquiryMapper.updateInquiryNumber(inquiryId, inquiryNumber);
            careDiaryMapper.linkReportInquiry(reportId, inquiryId);
            return inquiryNumber;
        } catch (RuntimeException e) {
            // 문의 생성이 실패해도 신고 접수는 남아야 한다. 임계치에 닿아 내린 글은
            // 접수번호를 못 줘도 노출을 멈추는 쪽이 먼저다.
            log.warn("[REPORT_INQUIRY_FAILED] 신고 문의 생성 실패 - reportId: {}", reportId, e);
            return null;
        }
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

        List<Map<String, Object>> images = careDiaryMapper.findImagesByDiaryIds(List.of(diaryId));
        List<String> imageKeys = images.stream()
                .map(image -> text(image, "imageUrl"))
                .filter(key -> key != null && !key.isBlank())
                .collect(Collectors.toList());
        // 공개했던 일기는 CDN에 사본이 남아 있다. 원본만 지우면 주소를 아는 사람에게는
        // 사진이 영구히 보인다.
        List<String> publicKeys = images.stream()
                .map(image -> text(image, "publicImageKey"))
                .filter(key -> key != null && !key.isBlank())
                .collect(Collectors.toList());
        if (careDiaryMapper.softDelete(diaryId) != 1) {
            throw BusinessException.notFound("삭제할 일기를 찾을 수 없습니다.");
        }
        arrangeCommittedCleanup(imageKeys);
        arrangeCommittedPublicCleanup(publicKeys);
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

    /** DB가 롤백되면 방금 만든 공개 사본은 참조가 없으므로 지운다. */
    private void arrangeRollbackPublicCleanup(List<String> publicKeys) {
        if (publicKeys.isEmpty() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    publicKeys.forEach(CareDiaryServiceImpl.this::unpublishQuietly);
                }
            }
        });
    }

    /** 삭제 트랜잭션이 확정된 뒤에만 실제 파일을 제거한다. */
    /** 공개 사본 정리. 원본 정리와 같은 이유로 커밋 이후에 돌린다. */
    private void arrangeCommittedPublicCleanup(List<String> publicKeys) {
        if (publicKeys.isEmpty()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publicKeys.forEach(fileStorage::unpublish);
                }
            });
        } else {
            publicKeys.forEach(fileStorage::unpublish);
        }
    }

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

    private void unpublishQuietly(String publicKey) {
        if (publicKey == null || publicKey.isBlank()) return;
        try {
            fileStorage.unpublish(publicKey);
        } catch (RuntimeException e) {
            log.warn("[CARE_DIARY_PUBLIC_CLEANUP_FAILED] 공개 사본 정리 실패 - key: {}", publicKey, e);
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
                .visibility(text(row, "visibility"))
                .hiddenByReport(value(row, "hiddenByReportAt") != null)
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
