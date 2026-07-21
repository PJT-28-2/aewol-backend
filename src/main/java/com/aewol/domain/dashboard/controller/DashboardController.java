package com.aewol.domain.dashboard.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.dashboard.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@Tag(name = "Dashboard", description = "지출 대시보드 API")
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "월별 지출 요약")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSummary(
            @AuthenticationPrincipal String memberId,
            @RequestParam(defaultValue = "#{T(java.time.YearMonth).now().toString()}") String month) {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getMonthlySummary(memberId, month)));
    }

    @Operation(summary = "카테고리별 지출")
    @GetMapping("/category")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getByCategory(
            @AuthenticationPrincipal String memberId,
            @RequestParam(required = false) String petId) {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getCategoryBreakdown(memberId, petId)));
    }
}
