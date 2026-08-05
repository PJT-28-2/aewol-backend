package com.aewol.domain.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SignupEmailCodeResponse {
    private final long expiresInSeconds;
}
