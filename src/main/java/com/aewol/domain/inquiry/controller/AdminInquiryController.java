package com.aewol.domain.inquiry.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.inquiry.dto.InquiryAnswerRequest;
import com.aewol.domain.inquiry.dto.InquiryDetailResponse;
import com.aewol.domain.inquiry.dto.InquiryListResponse;
import com.aewol.domain.inquiry.service.InquiryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin - Inquiry", description = "1:1 문의 관리 API")
@RestController
@RequestMapping("/api/admin/inquiries")
@RequiredArgsConstructor
public class AdminInquiryController {

    private final InquiryService inquiryService;

    @Operation(summary = "전체 문의 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<InquiryListResponse>> getInquiries(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(ApiResponse.success(inquiryService.getAdminInquiries(status, page, size)));
    }

    @Operation(summary = "문의 상세 조회")
    @GetMapping("/{inquiryId}")
    public ResponseEntity<ApiResponse<InquiryDetailResponse>> getInquiry(@PathVariable String inquiryId) {
        return ResponseEntity.ok(ApiResponse.success(inquiryService.getAdminInquiry(inquiryId)));
    }

    @Operation(summary = "문의 답변 등록 또는 수정")
    @PutMapping("/{inquiryId}/answer")
    public ResponseEntity<ApiResponse<InquiryDetailResponse>> answerInquiry(
            @AuthenticationPrincipal String adminId,
            @PathVariable String inquiryId,
            @Valid @RequestBody InquiryAnswerRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                inquiryService.answerInquiry(adminId, inquiryId, request.getAnswer())));
    }
}
