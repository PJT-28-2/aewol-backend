package com.aewol.domain.auth.dto;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PasswordResetEmailRequest {

    @NotBlank
    @Email
    @Size(max = 100)
    private String email;
}
