package com.aewol.domain.auth.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AccountFindVerifyRequest {

    @NotBlank
    @Size(max = 100)
    private String requestId;

    @NotBlank
    @Pattern(regexp = "\\d{6}")
    private String verificationCode;
}
