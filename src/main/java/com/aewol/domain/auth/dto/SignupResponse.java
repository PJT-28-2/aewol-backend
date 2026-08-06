package com.aewol.domain.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SignupResponse {
    private final Long userId;
    private final String email;
    private final String name;
}
