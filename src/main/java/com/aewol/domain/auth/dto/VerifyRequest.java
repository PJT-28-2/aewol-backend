package com.aewol.domain.auth.dto;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class VerifyRequest {
    @NotBlank @Email
    private String email;
    @NotBlank
    private String code;
}
