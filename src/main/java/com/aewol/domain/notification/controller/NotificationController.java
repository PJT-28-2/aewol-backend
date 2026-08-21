package com.aewol.domain.notification.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.notification.dto.NotificationListResponse;
import com.aewol.domain.notification.dto.NotificationUnreadCountResponse;
import com.aewol.domain.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notification", description = "인앱 알림함 API")
@RestController
@RequestMapping("/api/users/me/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "내 알림 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<NotificationListResponse>> getNotifications(
            @AuthenticationPrincipal String memberId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                "알림 목록을 조회했습니다.",
                notificationService.getNotifications(memberId, page, size)));
    }

    @Operation(summary = "미읽음 알림 개수 조회")
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<NotificationUnreadCountResponse>> getUnreadCount(
            @AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(
                "미읽음 알림 개수를 조회했습니다.",
                new NotificationUnreadCountResponse(notificationService.getUnreadCount(memberId))));
    }

    @Operation(summary = "특정 알림 읽음 처리")
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<Void>> markAsRead(
            @AuthenticationPrincipal String memberId,
            @PathVariable String notificationId) {
        notificationService.markAsRead(memberId, notificationId);
        return ResponseEntity.ok(ApiResponse.success("알림을 읽음 처리했습니다.", null));
    }

    @Operation(summary = "모든 알림 읽음 처리")
    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<Void>> markAllAsRead(
            @AuthenticationPrincipal String memberId) {
        notificationService.markAllAsRead(memberId);
        return ResponseEntity.ok(ApiResponse.success("모든 알림을 읽음 처리했습니다.", null));
    }
}
