package com.aewol.domain.activity.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.activity.service.ActivityLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "ActivityLog", description = "활동 로그 API")
@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
public class ActivityLogController {

    private final ActivityLogService activityLogService;

    @Operation(summary = "내 활동 로그 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyLogs(
            @AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(activityLogService.getLogsByMember(memberId)));
    }
}
