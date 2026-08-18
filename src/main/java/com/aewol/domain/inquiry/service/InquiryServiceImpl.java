package com.aewol.domain.inquiry.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.storage.FileStorage;
import com.aewol.domain.inquiry.dto.InquiryCreateResponse;
import com.aewol.domain.inquiry.dto.InquiryDetailResponse;
import com.aewol.domain.inquiry.dto.InquiryListItemResponse;
import com.aewol.domain.inquiry.dto.InquiryListResponse;
import com.aewol.domain.inquiry.mapper.InquiryMapper;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
@RequiredArgsConstructor
public class InquiryServiceImpl implements InquiryService {

    // api_명세서.md: "category는 FAQ와 동일한 값 사용"
    private static final Set<String> ALLOWED_CATEGORIES =
            Set.of("지갑·버킷", "보험", "계좌연동", "공동양육", "회원정보", "기타");
    private static final Set<String> ALLOWED_STATUSES = Set.of("WAITING", "ANSWERED");
    private static final int MAX_ATTACHMENTS = 3;
    private static final long MAX_ATTACHMENT_SIZE_BYTES = 10L * 1024 * 1024;
    private static final String ATTACHMENT_SUB_DIR = "inquiries";
    private static final Map<String, Set<String>> ALLOWED_FILE_TYPES = Map.of(
            "image/jpeg", Set.of("jpg", "jpeg"),
            "image/png", Set.of("png"),
            "application/pdf", Set.of("pdf")
    );

    private final InquiryMapper inquiryMapper;
    private final FileStorage fileStorage;

    @Override
    @Transactional
    public InquiryCreateResponse createInquiry(String memberId, String category, String title, String content,
                                                String replyEmail, List<MultipartFile> attachments) {
        validateCategory(category);
        if (title == null || title.isBlank()) {
            throw new BusinessException("제목을 입력해주세요");
        }
        if (content == null || content.isBlank()) {
            throw new BusinessException("내용을 입력해주세요");
        }
        if (replyEmail == null || replyEmail.isBlank()) {
            throw new BusinessException("답변받을 이메일을 입력해주세요");
        }

        // 빈 파일 파트(파일을 선택 안 했을 때 브라우저가 빈 MultipartFile을 같이 보내는 경우)는
        // 첨부 안 한 것으로 취급한다(PetServiceImpl.validateDocument와 동일한 방어).
        List<MultipartFile> files = attachments == null ? List.of()
                : attachments.stream().filter(f -> f != null && !f.isEmpty()).collect(Collectors.toList());
        if (files.size() > MAX_ATTACHMENTS) {
            throw new BusinessException("첨부파일은 최대 " + MAX_ATTACHMENTS + "개까지 업로드할 수 있어요");
        }
        // 형식/용량 검증을 먼저 전부 끝내고 나서 업로드를 시작한다 — 업로드 도중에 실패하면
        // 이미 디스크에 쓴 앞선 파일들을 다시 지워야 하는데, 애초에 뒤쪽 파일 하나가
        // 형식이 잘못된 것 때문에 그런 정리가 필요해지는 상황 자체를 피하기 위해서다.
        List<String> storageExtensions = files.stream().map(this::validateAttachment).collect(Collectors.toList());

        Map<String, Object> inquiry = new HashMap<>();
        inquiry.put("memberId", memberId);
        inquiry.put("category", category);
        inquiry.put("title", title);
        inquiry.put("content", content);
        inquiry.put("replyEmail", replyEmail);
        inquiryMapper.insert(inquiry); // inquiry_id AUTO_INCREMENT로 채워짐

        if (inquiry.get("inquiryId") == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "문의 등록에 실패했어요. 다시 시도해주세요");
        }
        String inquiryId = String.valueOf(inquiry.get("inquiryId"));

        // AEW-YYYYMMDD-{inquiry_id를 4자리 이상으로 zero-pad} — 별도 채번 카운터/락 없이,
        // 이미 유일함이 보장된 AUTO_INCREMENT PK를 그대로 활용해서 동시 요청에도 접수번호가
        // 겹칠 수 없게 한다.
        String inquiryNumber = "AEW-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%04d", Long.parseLong(inquiryId));
        inquiryMapper.updateInquiryNumber(inquiryId, inquiryNumber);

        // FileStorage로 저장하는 키는 "/uploads/..." URL이 아니라 "inquiries/xxx.png" 형태다.
        // DB(file_url 컬럼)에는 이 키를 그대로 저장하고, 조회 시 fileStorage.signedUrl()이
        // 만들어주는 서명 URL로 내려준다(과거 FileUtil로 저장된 "/uploads/..." 형식의 기존
        // 데이터도 LocalFileStorage.normalize()가 함께 처리하므로 마이그레이션은 불필요하다).
        List<String> uploadedKeys = new ArrayList<>();
        try {
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                String fileKey;
                try {
                    fileKey = fileStorage.store(file.getBytes(), ATTACHMENT_SUB_DIR, storageExtensions.get(i));
                } catch (IOException e) {
                    throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "첨부파일 저장에 실패했어요");
                }
                uploadedKeys.add(fileKey);

                Map<String, Object> attachment = new HashMap<>();
                attachment.put("inquiryId", inquiryId);
                attachment.put("fileName", extractOriginalFilename(file));
                attachment.put("fileUrl", fileKey);
                attachment.put("fileSize", file.getSize());
                inquiryMapper.insertAttachment(attachment);
            }
        } catch (RuntimeException e) {
            // 이 메서드가 @Transactional이라 DB(inquiry/inquiry_attachment insert)는 이
            // 예외로 자동 롤백되지만, 파일시스템은 트랜잭션에 안 묶여 있어서 이미 저장한
            // 파일은 직접 지워야 한다(PetServiceImpl의 파일 정리와 같은 이유).
            // FileStorage.delete()는 실패를 내부에서 로그만 남기고 삼키므로 별도 try/catch가 필요 없다.
            uploadedKeys.forEach(fileStorage::delete);
            throw e;
        }

        return InquiryCreateResponse.builder()
                .inquiryId(inquiryId)
                .inquiryNumber(inquiryNumber)
                .build();
    }

    @Override
    public InquiryListResponse getInquiries(String memberId, String status, int page, int size) {
        if (status != null && !status.isBlank() && !ALLOWED_STATUSES.contains(status)) {
            throw new BusinessException("status는 WAITING 또는 ANSWERED만 가능해요");
        }
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 10 : size;

        List<Map<String, Object>> rows =
                inquiryMapper.findByMemberId(memberId, status, safeSize + 1, safePage * safeSize);
        boolean hasNext = rows.size() > safeSize;
        List<Map<String, Object>> pageRows = hasNext ? rows.subList(0, safeSize) : rows;

        List<InquiryListItemResponse> items = pageRows.stream()
                .map(this::toListItemResponse)
                .collect(Collectors.toList());
        return InquiryListResponse.builder().inquiries(items).hasNext(hasNext).build();
    }

    @Override
    public InquiryDetailResponse getInquiry(String memberId, String inquiryId) {
        Map<String, Object> inquiry = inquiryMapper.findByIdAndMemberId(inquiryId, memberId);
        if (inquiry == null) {
            // 존재하지 않는 문의와 다른 회원 소유의 문의를 구분해서 알려주지 않는다
            // (setPrimaryAccount/disconnectAccount와 동일한 방식).
            throw BusinessException.notFound("문의를 찾을 수 없어요");
        }
        List<String> attachments = inquiryMapper.findAttachmentsByInquiryId(inquiryId).stream()
                .map(a -> (String) a.get("file_url"))
                .map(fileStorage::signedUrl)
                .collect(Collectors.toList());
        return toDetailResponse(inquiry, attachments);
    }

    private void validateCategory(String category) {
        if (category == null || !ALLOWED_CATEGORIES.contains(category)) {
            throw new BusinessException("category는 " + String.join(", ", ALLOWED_CATEGORIES) + " 중 하나여야 해요");
        }
    }

    private String validateAttachment(MultipartFile file) {
        if (file.getSize() > MAX_ATTACHMENT_SIZE_BYTES) {
            throw new BusinessException("첨부파일은 10MB 이하만 업로드할 수 있어요");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        Set<String> extensions = ALLOWED_FILE_TYPES.get(contentType);
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "";
        if (extensions == null || !extensions.contains(extension)) {
            throw new BusinessException("JPG, JPEG, PNG, PDF 파일만 업로드할 수 있어요");
        }
        return "image/jpeg".equals(contentType) ? "jpg" : extension;
    }

    private String extractOriginalFilename(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new BusinessException("파일명이 올바르지 않습니다.");
        }
        String normalized = originalFilename.replace('\\', '/');
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (filename.isBlank() || filename.equals(".") || filename.equals("..")
                || filename.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException("파일명이 올바르지 않습니다.");
        }
        return filename;
    }

    private InquiryListItemResponse toListItemResponse(Map<String, Object> row) {
        return InquiryListItemResponse.builder()
                .inquiryId(String.valueOf(row.get("inquiry_id")))
                .title((String) row.get("title"))
                .status((String) row.get("status"))
                .createdAt(toDateString(row.get("created_at")))
                .build();
    }

    private InquiryDetailResponse toDetailResponse(Map<String, Object> row, List<String> attachments) {
        return InquiryDetailResponse.builder()
                .inquiryId(String.valueOf(row.get("inquiry_id")))
                .inquiryNumber((String) row.get("inquiry_number"))
                .category((String) row.get("category"))
                .title((String) row.get("title"))
                .content((String) row.get("content"))
                .replyEmail((String) row.get("reply_email"))
                .attachments(attachments)
                .status((String) row.get("status"))
                .answer((String) row.get("answer"))
                .createdAt(toDateString(row.get("created_at")))
                .answeredAt(toDateString(row.get("answered_at")))
                .build();
    }

    /** MyBatis 드라이버 설정에 따라 DATETIME 컬럼이 Timestamp/LocalDateTime로 다르게 매핑될 수 있다 */
    private static String toDateString(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp) return ((Timestamp) value).toLocalDateTime().toString();
        if (value instanceof LocalDateTime) return value.toString();
        return String.valueOf(value);
    }
}
