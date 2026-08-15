package com.aewol.external.sms;

public interface SmsSender {
    void send(String to, String text);
}
