package com.aewol.domain.notification.service;

import com.aewol.domain.notification.dto.NotificationSettingResponse;
import com.aewol.domain.notification.dto.NotificationSettingUpdateRequest;

public interface NotificationSettingService {
    NotificationSettingResponse getSettings(String memberId);
    NotificationSettingResponse updateSettings(String memberId, NotificationSettingUpdateRequest request);
}
