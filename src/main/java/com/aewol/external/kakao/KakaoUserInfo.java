package com.aewol.external.kakao;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class KakaoUserInfo {
    private String providerId;
    private String email;
    private String name;
}
