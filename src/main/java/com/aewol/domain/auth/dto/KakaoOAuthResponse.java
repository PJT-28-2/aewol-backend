package com.aewol.domain.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class KakaoOAuthResponse {
    private KakaoAuthStatus authStatus;
    private String accessToken;
    private String refreshToken;
    private String registrationToken;

    public static KakaoOAuthResponse loginComplete(TokenResponse tokens) {
        return KakaoOAuthResponse.builder()
                .authStatus(KakaoAuthStatus.LOGIN_COMPLETE)
                .accessToken(tokens.getAccessToken())
                .refreshToken(tokens.getRefreshToken())
                .registrationToken(null)
                .build();
    }

    public static KakaoOAuthResponse additionalInfoRequired(String registrationToken) {
        return KakaoOAuthResponse.builder()
                .authStatus(KakaoAuthStatus.ADDITIONAL_INFO_REQUIRED)
                .accessToken(null)
                .refreshToken(null)
                .registrationToken(registrationToken)
                .build();
    }

    public static KakaoOAuthResponse accountRestored(TokenResponse tokens) {
        return KakaoOAuthResponse.builder()
                .authStatus(KakaoAuthStatus.ACCOUNT_RESTORED)
                .accessToken(tokens.getAccessToken())
                .refreshToken(tokens.getRefreshToken())
                .registrationToken(null)
                .build();
    }
}
