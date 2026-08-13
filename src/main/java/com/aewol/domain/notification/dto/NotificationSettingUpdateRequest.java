package com.aewol.domain.notification.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class NotificationSettingUpdateRequest {
    private Boolean paymentEnabled;
    private Boolean recurringPaymentEnabled;
    private Boolean familyShareEnabled;
    private Boolean communityEnabled;
    private Boolean marketingEnabled;

    public boolean hasChanges() {
        return paymentEnabled != null
                || recurringPaymentEnabled != null
                || familyShareEnabled != null
                || communityEnabled != null
                || marketingEnabled != null;
    }
}
