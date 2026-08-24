package com.aewol.domain.share.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.share.dto.AdminDiaryReportDetailResponse;
import com.aewol.domain.share.dto.AdminDiaryReportListResponse;
import com.aewol.domain.share.dto.AdminDiaryReportResolutionRequest;
import com.aewol.domain.share.service.CareDiaryService;
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

@Tag(name = "AdminDiaryReport", description = "관리자 멍스타그램 신고 처리 API")
@RestController
@RequestMapping("/api/admin/diary-reports")
@RequiredArgsConstructor
public class AdminDiaryReportController {
    private final CareDiaryService careDiaryService;

    @Operation(summary = "신고 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<AdminDiaryReportListResponse>> getReports(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(careDiaryService.getAdminReports(status, page, size)));
    }

    @Operation(summary = "신고 상세 조회")
    @GetMapping("/{reportId}")
    public ResponseEntity<ApiResponse<AdminDiaryReportDetailResponse>> getReport(
            @PathVariable String reportId) {
        return ResponseEntity.ok(ApiResponse.success(careDiaryService.getAdminReport(reportId)));
    }

    @Operation(summary = "신고 처리", description = "KEEP_HIDDEN은 숨김을 유지하고 RESTORE는 게시물을 복원합니다.")
    @PutMapping("/{reportId}/resolution")
    public ResponseEntity<ApiResponse<AdminDiaryReportDetailResponse>> resolve(
            @AuthenticationPrincipal String adminId,
            @PathVariable String reportId,
            @Valid @RequestBody AdminDiaryReportResolutionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                careDiaryService.resolveAdminReport(adminId, reportId, request)));
    }
}
