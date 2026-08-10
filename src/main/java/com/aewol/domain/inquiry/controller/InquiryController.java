package com.aewol.domain.inquiry.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.inquiry.dto.InquiryCreateResponse;
import com.aewol.domain.inquiry.dto.InquiryDetailResponse;
import com.aewol.domain.inquiry.dto.InquiryListResponse;
import com.aewol.domain.inquiry.service.InquiryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** PetController의 접종증명서 업로드와 동일하게, 멀티파트 필드는 개별 @RequestParam으로 받는다(요청 DTO에 바인딩하지 않음). */
@Tag(name = "Inquiry", description = "1:1 문의 API")
@RestController
@RequestMapping("/api/support/inquiries")
@RequiredArgsConstructor
public class InquiryController {

    private final InquiryService inquiryService;

    @Operation(summary = "1:1 문의 등록")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<InquiryCreateResponse>> createInquiry(
            @AuthenticationPrincipal String memberId,
            @RequestParam String category,
            @RequestParam String title,
            @RequestParam String content,
            @RequestParam String replyEmail,
            @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(
                        inquiryService.createInquiry(memberId, category, title, content, replyEmail, attachments)));
    }

    @Operation(summary = "내 문의 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<InquiryListResponse>> getInquiries(
            @AuthenticationPrincipal String memberId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(ApiResponse.success(inquiryService.getInquiries(memberId, status, page, size)));
    }

    @Operation(summary = "문의 상세 조회")
    @GetMapping("/{inquiryId}")
    public ResponseEntity<ApiResponse<InquiryDetailResponse>> getInquiry(
            @AuthenticationPrincipal String memberId,
            @PathVariable String inquiryId) {
        return ResponseEntity.ok(ApiResponse.success(inquiryService.getInquiry(memberId, inquiryId)));
    }
}
