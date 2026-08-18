package com.aewol.domain.auth.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class KakaoPhoneSendCodeRequest {

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_-]{43}")
    private String registrationToken;

    @NotBlank
    @Pattern(regexp = "^010\\d{8}$")
    private String phone;
}
