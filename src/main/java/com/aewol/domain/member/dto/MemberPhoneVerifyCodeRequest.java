package com.aewol.domain.member.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MemberPhoneVerifyCodeRequest {

    @NotBlank
    @Pattern(regexp = "^010\\d{8}$")
    private String phone;

    @NotBlank
    @Pattern(regexp = "\\d{6}")
    private String verificationCode;
}
