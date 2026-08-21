package com.aewol.domain.notification.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NotificationListResponse {
    private List<NotificationResponse> notifications;
    private int unreadCount;
    private boolean hasNext;
    private int page;
    private int size;
}
