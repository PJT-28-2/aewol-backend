package com.aewol.domain.auth.dto;

import com.aewol.common.validation.ValidPassword;
import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PasswordResetRequest {

    @NotBlank
    private String resetToken;

    @NotBlank
    @ValidPassword
    private String newPassword;
}
