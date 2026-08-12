package com.aewol.domain.auth.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PasswordResetRequest {

    @NotBlank
    private String resetToken;

    @NotBlank
    @Size(min = 8, max = 20)
    private String newPassword;
}
