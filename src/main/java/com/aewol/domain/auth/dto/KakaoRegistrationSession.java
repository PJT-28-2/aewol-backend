package com.aewol.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KakaoRegistrationSession {
    private String providerId;
    private String email;
    private String name;
    private String verifiedPhone;

    public KakaoRegistrationSession(String providerId, String email, String name) {
        this(providerId, email, name, null);
    }
}
