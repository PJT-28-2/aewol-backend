package com.aewol.domain.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class NotificationUnreadCountResponse {
    private int unreadCount;
}
