package com.aewol.domain.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class NotificationSettingResponse {
    private final boolean paymentEnabled;
    private final boolean recurringPaymentEnabled;
    private final boolean familyShareEnabled;
    private final boolean communityEnabled;
    private final boolean marketingEnabled;
}
