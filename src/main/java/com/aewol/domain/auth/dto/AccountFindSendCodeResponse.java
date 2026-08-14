package com.aewol.domain.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AccountFindSendCodeResponse {
    private final String requestId;
    private final long expiresInSeconds;
}
