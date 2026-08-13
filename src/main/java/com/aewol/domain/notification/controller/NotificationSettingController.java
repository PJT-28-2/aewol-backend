package com.aewol.domain.notification.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.notification.dto.NotificationSettingResponse;
import com.aewol.domain.notification.dto.NotificationSettingUpdateRequest;
import com.aewol.domain.notification.service.NotificationSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notification Setting", description = "사용자 알림 설정 API")
@RestController
@RequestMapping("/api/users/me/settings/notifications")
@RequiredArgsConstructor
public class NotificationSettingController {

    private final NotificationSettingService notificationSettingService;

    @Operation(summary = "알림 설정 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<NotificationSettingResponse>> getSettings(
            @AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(
                "알림 설정을 조회했습니다.", notificationSettingService.getSettings(memberId)));
    }

    @Operation(summary = "알림 설정 변경")
    @PatchMapping
    public ResponseEntity<ApiResponse<NotificationSettingResponse>> updateSettings(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody NotificationSettingUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "알림 설정이 변경되었습니다.",
                notificationSettingService.updateSettings(memberId, request)));
    }
}
