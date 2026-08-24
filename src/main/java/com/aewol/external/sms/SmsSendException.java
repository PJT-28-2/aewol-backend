package com.aewol.external.sms;

import lombok.Getter;

@Getter
public class SmsSendException extends RuntimeException {

    private final SmsFailureReason reason;

    public SmsSendException(String message) {
        this(message, SmsFailureReason.PROVIDER_REJECTED, null);
    }

    public SmsSendException(String message, Throwable cause) {
        this(message, SmsFailureReason.TRANSPORT_OR_HTTP, cause);
    }

    public SmsSendException(String message, SmsFailureReason reason) {
        this(message, reason, null);
    }

    public SmsSendException(String message, SmsFailureReason reason, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }
}
