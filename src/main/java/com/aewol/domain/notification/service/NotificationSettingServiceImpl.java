package com.aewol.domain.notification.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.notification.dto.NotificationSettingResponse;
import com.aewol.domain.notification.dto.NotificationSettingUpdateRequest;
import com.aewol.domain.notification.mapper.NotificationSettingMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationSettingServiceImpl implements NotificationSettingService {

    private final NotificationSettingMapper notificationSettingMapper;

    @Override
    public NotificationSettingResponse getSettings(String memberId) {
        return response(findSettings(memberId));
    }

    @Override
    @Transactional
    public NotificationSettingResponse updateSettings(
            String memberId, NotificationSettingUpdateRequest request) {
        findSettings(memberId);
        if (request.hasChanges()) {
            notificationSettingMapper.updatePartial(
                    memberId,
                    request.getPaymentEnabled(),
                    request.getRecurringPaymentEnabled(),
                    request.getFamilyShareEnabled(),
                    request.getCommunityEnabled(),
                    request.getMarketingEnabled());
        }
        return response(findSettings(memberId));
    }

    private Map<String, Object> findSettings(String memberId) {
        Map<String, Object> settings = notificationSettingMapper.findByMemberId(memberId);
        if (settings == null) {
            throw new BusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "알림 설정을 처리하는 중 오류가 발생했습니다.");
        }
        return settings;
    }

    private NotificationSettingResponse response(Map<String, Object> settings) {
        return new NotificationSettingResponse(
                booleanValue(settings.get("payment_enabled")),
                booleanValue(settings.get("recurring_payment_enabled")),
                booleanValue(settings.get("family_share_enabled")),
                booleanValue(settings.get("community_enabled")),
                booleanValue(settings.get("marketing_enabled")));
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value instanceof Number && ((Number) value).intValue() == 1;
    }
}
