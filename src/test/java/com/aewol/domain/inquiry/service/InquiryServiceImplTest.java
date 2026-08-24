package com.aewol.domain.inquiry.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.storage.FileStorage;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.inquiry.dto.InquiryCreateResponse;
import com.aewol.domain.inquiry.dto.InquiryDetailResponse;
import com.aewol.domain.inquiry.dto.InquiryListResponse;
import com.aewol.domain.inquiry.mapper.InquiryMapper;
import com.aewol.domain.share.mapper.CareDiaryMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class InquiryServiceImplTest {

    @Mock InquiryMapper inquiryMapper;
    @Mock FileStorage fileStorage;
    @Mock RedisRateLimiter redisRateLimiter;
    @Mock CareDiaryMapper careDiaryMapper;
    @InjectMocks InquiryServiceImpl service;

    private static final String MEMBER_ID = "9001";
    private static final String INQUIRY_ID = "25";

    @Test
    @DisplayName("첨부파일 없이 문의를 등록하면 inquiry_id 기반 접수번호를 채번한다")
    void should_createInquiry_withoutAttachments() {
        doAnswer(invocation -> {
            Map<String, Object> arg = invocation.getArgument(0);
            arg.put("inquiryId", 25L);
            return null;
        }).when(inquiryMapper).insert(any());

        InquiryCreateResponse result = service.createInquiry(
                MEMBER_ID, "계좌연동", "계좌 연동이 안돼요", "1원 인증까지 했는데 등록이 안돼요", "user@example.com", null);

        assertEquals("25", result.getInquiryId());
        assertTrue(result.getInquiryNumber().matches("AEW-\\d{8}-0025"));
        verify(inquiryMapper).updateInquiryNumber("25", result.getInquiryNumber());
        verify(inquiryMapper, never()).insertAttachment(any());
        verifyNoInteractions(fileStorage);
    }

    @Test
    @DisplayName("첨부파일이 있으면 업로드 후 첨부 테이블에 각각 저장한다")
    void should_uploadAndInsertAttachments_whenFilesGiven() {
        doAnswer(invocation -> {
            Map<String, Object> arg = invocation.getArgument(0);
            arg.put("inquiryId", 25L);
            return null;
        }).when(inquiryMapper).insert(any());
        when(fileStorage.store(any(), eq("inquiries"), eq("jpg"))).thenReturn("inquiries/a.jpg");

        MockMultipartFile file = new MockMultipartFile("attachments", "image1.jpg", "image/jpeg", new byte[]{1, 2, 3});

        service.createInquiry(MEMBER_ID, "보험", "제목", "내용", "user@example.com", List.of(file));

        verify(fileStorage).store(any(), eq("inquiries"), eq("jpg"));
        verify(inquiryMapper).insertAttachment(any());
    }

    @Test
    @DisplayName("webp 첨부파일도 업로드 후 첨부 테이블에 저장한다")
    void should_uploadAndInsertAttachment_when_fileIsWebp() {
        doAnswer(invocation -> {
            Map<String, Object> arg = invocation.getArgument(0);
            arg.put("inquiryId", 25L);
            return null;
        }).when(inquiryMapper).insert(any());
        when(fileStorage.store(any(), eq("inquiries"), eq("webp"))).thenReturn("inquiries/a.webp");

        MockMultipartFile file = new MockMultipartFile("attachments", "image1.webp", "image/webp", new byte[]{1, 2, 3});

        service.createInquiry(MEMBER_ID, "보험", "제목", "내용", "user@example.com", List.of(file));

        verify(fileStorage).store(any(), eq("inquiries"), eq("webp"));
        verify(inquiryMapper).insertAttachment(any());
    }

    @Test
    @DisplayName("첨부파일이 3개를 초과하면 400 예외를 던지고 업로드를 시도하지 않는다")
    void should_throwBadRequest_when_moreThanThreeAttachments() {
        List<MultipartFile> files = List.of(
                new MockMultipartFile("attachments", "a.jpg", "image/jpeg", new byte[]{1}),
                new MockMultipartFile("attachments", "b.jpg", "image/jpeg", new byte[]{1}),
                new MockMultipartFile("attachments", "c.jpg", "image/jpeg", new byte[]{1}),
                new MockMultipartFile("attachments", "d.jpg", "image/jpeg", new byte[]{1}));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createInquiry(MEMBER_ID, "기타", "제목", "내용", "user@example.com", files));

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(inquiryMapper, never()).insert(any());
    }

    @Test
    @DisplayName("허용되지 않은 파일 형식이면 400 예외를 던진다")
    void should_throwBadRequest_when_fileTypeNotAllowed() {
        MockMultipartFile file = new MockMultipartFile("attachments", "readme.txt", "text/plain", new byte[]{1});

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createInquiry(MEMBER_ID, "기타", "제목", "내용", "user@example.com", List.of(file)));

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(inquiryMapper, never()).insert(any());
    }

    @Test
    @DisplayName("허용되지 않은 category면 400 예외를 던진다")
    void should_throwBadRequest_when_categoryNotAllowed() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createInquiry(MEMBER_ID, "존재하지않는카테고리", "제목", "내용", "user@example.com", null));

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(inquiryMapper, never()).insert(any());
    }

    @Test
    @DisplayName("제목이 200자를 넘으면 저장하지 않고 400 예외를 던진다")
    void should_throwBadRequest_when_titleExceedsMaxLength() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createInquiry(MEMBER_ID, "기타", "가".repeat(201), "내용", "user@example.com", null));

        assertEquals("제목은 200자 이하로 입력해주세요", ex.getMessage());
        verify(inquiryMapper, never()).insert(any());
        verifyNoInteractions(redisRateLimiter);
    }

    @Test
    @DisplayName("내용이 5000자를 넘으면 저장하지 않고 400 예외를 던진다")
    void should_throwBadRequest_when_contentExceedsMaxLength() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createInquiry(MEMBER_ID, "기타", "제목", "가".repeat(5001), "user@example.com", null));

        assertEquals("내용은 5000자 이하로 입력해주세요", ex.getMessage());
        verify(inquiryMapper, never()).insert(any());
        verifyNoInteractions(redisRateLimiter);
    }

    @Test
    @DisplayName("답변 이메일이 형식이 아니면 저장하지 않고 400 예외를 던진다")
    void should_throwBadRequest_when_replyEmailIsInvalid() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createInquiry(MEMBER_ID, "기타", "제목", "내용", "not-an-email", null));

        assertEquals("답변받을 이메일 형식이 올바르지 않아요", ex.getMessage());
        verify(inquiryMapper, never()).insert(any());
        verifyNoInteractions(fileStorage);
    }

    @Test
    @DisplayName("짧은 시간에 문의를 너무 많이 쓰면 첨부 업로드 전에 429로 거절한다")
    void should_throwTooManyRequests_when_createRateLimitExceeded() {
        when(redisRateLimiter.incrementWithExpiry("rate:inquiry-create:" + MEMBER_ID, 15 * 60L))
                .thenReturn(6L);
        MockMultipartFile file = new MockMultipartFile("attachments", "a.jpg", "image/jpeg", new byte[]{1});

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createInquiry(MEMBER_ID, "기타", "제목", "내용", "user@example.com", List.of(file)));

        assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        verify(inquiryMapper, never()).insert(any());
        verifyNoInteractions(fileStorage);
    }

    @Test
    @DisplayName("두 번째 파일 업로드가 실패하면 첫 번째 파일도 삭제하고 예외를 던진다")
    void should_deleteUploadedFiles_when_laterFileUploadFails() {
        doAnswer(invocation -> {
            Map<String, Object> arg = invocation.getArgument(0);
            arg.put("inquiryId", 25L);
            return null;
        }).when(inquiryMapper).insert(any());
        MockMultipartFile first = new MockMultipartFile("attachments", "a.jpg", "image/jpeg", new byte[]{1});
        MockMultipartFile second = new MockMultipartFile("attachments", "b.png", "image/png", new byte[]{1});
        when(fileStorage.store(any(), eq("inquiries"), eq("jpg"))).thenReturn("inquiries/a.jpg");
        when(fileStorage.store(any(), eq("inquiries"), eq("png")))
                .thenThrow(new BusinessException("파일을 저장하지 못했어요. 잠시 후 다시 시도해 주세요."));

        assertThrows(BusinessException.class,
                () -> service.createInquiry(MEMBER_ID, "기타", "제목", "내용", "user@example.com", List.of(first, second)));

        verify(fileStorage).delete("inquiries/a.jpg");
    }

    @Test
    @DisplayName("문의 목록은 status 필터와 hasNext를 함께 반환한다")
    void should_returnHasNextTrue_when_moreRowsThanPageSize() {
        when(inquiryMapper.findByMemberId(MEMBER_ID, "WAITING", 2, 0))
                .thenReturn(List.of(inquiryListRow("1"), inquiryListRow("2")));

        InquiryListResponse result = service.getInquiries(MEMBER_ID, "WAITING", 0, 1);

        assertEquals(1, result.getInquiries().size());
        assertTrue(result.isHasNext());
    }

    @Test
    @DisplayName("허용되지 않은 status로 목록을 조회하면 400 예외를 던진다")
    void should_throwBadRequest_when_statusInvalid() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getInquiries(MEMBER_ID, "DONE", 0, 10));

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("본인 문의를 상세 조회하면 첨부파일 URL 목록과 함께 반환한다")
    void should_returnAttachmentUrls_when_inquiryExists() {
        Map<String, Object> row = new HashMap<>();
        row.put("inquiry_id", INQUIRY_ID);
        row.put("inquiry_number", "AEW-20260722-0025");
        row.put("category", "계좌연동");
        row.put("title", "제목");
        row.put("content", "내용");
        row.put("reply_email", "user@example.com");
        row.put("status", "ANSWERED");
        row.put("answer", "확인해주세요");
        when(inquiryMapper.findByIdAndMemberId(INQUIRY_ID, MEMBER_ID)).thenReturn(row);
        Map<String, Object> attachment = new HashMap<>();
        attachment.put("file_url", "inquiries/a.jpg");
        when(inquiryMapper.findAttachmentsByInquiryId(INQUIRY_ID)).thenReturn(List.of(attachment));
        when(fileStorage.signedUrl("inquiries/a.jpg")).thenReturn("signed:inquiries/a.jpg");

        InquiryDetailResponse result = service.getInquiry(MEMBER_ID, INQUIRY_ID);

        assertEquals("확인해주세요", result.getAnswer());
        assertEquals(List.of("signed:inquiries/a.jpg"), result.getAttachments());
    }

    @Test
    @DisplayName("다른 회원 소유이거나 존재하지 않는 문의를 조회하면 404 예외를 던진다")
    void should_throwNotFound_when_inquiryDoesNotBelongToMember() {
        when(inquiryMapper.findByIdAndMemberId(INQUIRY_ID, MEMBER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getInquiry(MEMBER_ID, INQUIRY_ID));

        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("관리자 문의 목록은 모든 회원의 문의를 상태별로 페이지 조회한다")
    void should_returnAllInquiries_when_adminRequestsList() {
        Map<String, Object> first = inquiryListRow("1");
        first.put("inquiry_number", "AEW-20260824-0001");
        first.put("category", "보험");
        when(inquiryMapper.findAll("WAITING", 2, 0)).thenReturn(List.of(first, inquiryListRow("2")));

        InquiryListResponse result = service.getAdminInquiries("WAITING", 0, 1);

        assertEquals(1, result.getInquiries().size());
        assertEquals("AEW-20260824-0001", result.getInquiries().get(0).getInquiryNumber());
        assertEquals("보험", result.getInquiries().get(0).getCategory());
        assertTrue(result.isHasNext());
    }

    @Test
    @DisplayName("관리자는 회원 소유권 조건 없이 문의 상세를 조회한다")
    void should_returnInquiryDetail_when_adminRequestsDetail() {
        Map<String, Object> row = inquiryDetailRow("WAITING", null);
        when(inquiryMapper.findById(INQUIRY_ID)).thenReturn(row);
        when(inquiryMapper.findAttachmentsByInquiryId(INQUIRY_ID)).thenReturn(List.of());

        InquiryDetailResponse result = service.getAdminInquiry(INQUIRY_ID);

        assertEquals(INQUIRY_ID, result.getInquiryId());
        assertEquals("user@example.com", result.getReplyEmail());
        assertEquals("WAITING", result.getStatus());
    }

    @Test
    @DisplayName("관리자가 답변하면 공백을 제거해 저장하고 ANSWERED 상세를 반환한다")
    void should_updateAnswerAndReturnDetail_when_adminAnswers() {
        when(inquiryMapper.updateAnswer(INQUIRY_ID, "확인 후 조치했습니다.")).thenReturn(1);
        when(inquiryMapper.findById(INQUIRY_ID))
                .thenReturn(inquiryDetailRow("ANSWERED", "확인 후 조치했습니다."));
        when(inquiryMapper.findAttachmentsByInquiryId(INQUIRY_ID)).thenReturn(List.of());

        InquiryDetailResponse result = service.answerInquiry("admin-1", INQUIRY_ID, "  확인 후 조치했습니다.  ");

        verify(inquiryMapper).updateAnswer(INQUIRY_ID, "확인 후 조치했습니다.");
        verify(careDiaryMapper, never()).resolvePendingReportsByInquiryId(any(), any(), any(), any());
        assertEquals("ANSWERED", result.getStatus());
        assertEquals("확인 후 조치했습니다.", result.getAnswer());
    }

    @Test
    @DisplayName("신고 1건으로 게시물이 아직 공개 중이면 문의 답변은 DISMISS로 신고만 끝낸다")
    void should_dismissLinkedReport_when_diaryIsStillPublic() {
        when(inquiryMapper.updateAnswer(INQUIRY_ID, "확인 후 조치했습니다.")).thenReturn(1);
        when(careDiaryMapper.findLinkedReportDiaryByInquiryId(INQUIRY_ID))
                .thenReturn(linkedDiary("diary-1", null));
        when(inquiryMapper.findById(INQUIRY_ID))
                .thenReturn(inquiryDetailRow("ANSWERED", "확인 후 조치했습니다."));
        when(inquiryMapper.findAttachmentsByInquiryId(INQUIRY_ID)).thenReturn(List.of());

        service.answerInquiry("admin-1", INQUIRY_ID, "확인 후 조치했습니다.");

        verify(careDiaryMapper).resolvePendingReportsByInquiryId(
                INQUIRY_ID, "DISMISS", "확인 후 조치했습니다.", "admin-1");
        verify(careDiaryMapper, never()).hideByReport(any());
        verify(careDiaryMapper, never()).restoreByReport(any());
    }

    @Test
    @DisplayName("이미 신고로 숨겨진 게시물의 문의에 답하면 숨김을 유지한다")
    void should_keepHidden_when_linkedDiaryIsAlreadyHidden() {
        when(inquiryMapper.updateAnswer(INQUIRY_ID, "확인 후 조치했습니다.")).thenReturn(1);
        when(careDiaryMapper.findLinkedReportDiaryByInquiryId(INQUIRY_ID))
                .thenReturn(linkedDiary("diary-1", java.sql.Timestamp.valueOf("2026-08-21 10:00:00")));
        when(inquiryMapper.findById(INQUIRY_ID))
                .thenReturn(inquiryDetailRow("ANSWERED", "확인 후 조치했습니다."));
        when(inquiryMapper.findAttachmentsByInquiryId(INQUIRY_ID)).thenReturn(List.of());

        service.answerInquiry("admin-1", INQUIRY_ID, "확인 후 조치했습니다.");

        verify(careDiaryMapper).resolvePendingReportsByInquiryId(
                INQUIRY_ID, "KEEP_HIDDEN", "확인 후 조치했습니다.", "admin-1");
        verify(careDiaryMapper, never()).restoreByReport(any());
        verify(careDiaryMapper, never()).hideByReport(any());
    }

    @Test
    @DisplayName("존재하지 않는 문의에 답변하면 404 예외를 던진다")
    void should_throwNotFound_when_adminAnswersMissingInquiry() {
        when(inquiryMapper.updateAnswer(INQUIRY_ID, "답변")).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.answerInquiry("admin-1", INQUIRY_ID, "답변"));

        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatus());
        verify(inquiryMapper, never()).findById(any());
        verify(careDiaryMapper, never()).findLinkedReportDiaryByInquiryId(any());
        verify(careDiaryMapper, never()).resolvePendingReportsByInquiryId(any(), any(), any(), any());
    }

    @Test
    @DisplayName("빈 답변은 저장하지 않고 400 예외를 던진다")
    void should_throwBadRequest_when_adminAnswerIsBlank() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.answerInquiry("admin-1", INQUIRY_ID, "   "));

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(inquiryMapper, never()).updateAnswer(any(), any());
        verify(careDiaryMapper, never()).resolvePendingReportsByInquiryId(any(), any(), any(), any());
    }

    private Map<String, Object> inquiryDetailRow(String status, String answer) {
        Map<String, Object> row = new HashMap<>();
        row.put("inquiry_id", INQUIRY_ID);
        row.put("inquiry_number", "AEW-20260824-0025");
        row.put("category", "기타");
        row.put("title", "제목");
        row.put("content", "내용");
        row.put("reply_email", "user@example.com");
        row.put("status", status);
        row.put("answer", answer);
        return row;
    }

    private Map<String, Object> linkedDiary(String diaryId, Object hiddenByReportAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("diaryId", diaryId);
        row.put("hiddenByReportAt", hiddenByReportAt);
        return row;
    }

    private Map<String, Object> inquiryListRow(String inquiryId) {
        Map<String, Object> row = new HashMap<>();
        row.put("inquiry_id", inquiryId);
        row.put("title", "제목 " + inquiryId);
        row.put("status", "WAITING");
        row.put("created_at", "2026-08-08T00:00:00");
        return row;
    }
}
