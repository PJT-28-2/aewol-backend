package com.aewol.domain.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class KakaoRegistrationSession {
    private String providerId;
    private String email;
    private String name;
}
