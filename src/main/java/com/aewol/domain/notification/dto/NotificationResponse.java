package com.aewol.domain.notification.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NotificationResponse {
    private String notificationId;
    private String type;
    private String title;
    private String message;
    private String targetPath;
    private boolean read;
    private String readAt;
    private String createdAt;
}
