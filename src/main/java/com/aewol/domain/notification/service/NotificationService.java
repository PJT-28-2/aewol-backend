package com.aewol.domain.notification.service;

import com.aewol.domain.notification.dto.NotificationListResponse;

public interface NotificationService {
    NotificationListResponse getNotifications(String memberId, int page, int size);

    int getUnreadCount(String memberId);

    void markAsRead(String memberId, String notificationId);

    void markAllAsRead(String memberId);

    String createNotification(
            String memberId, String type, String title, String message, String targetPath);

    String createNotification(
            String memberId, String type, String title, String message, String targetPath,
            String eventKey);
}
