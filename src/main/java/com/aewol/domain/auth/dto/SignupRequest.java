package com.aewol.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SignupRequest {
    @NotBlank @Email
    private String email;
    @NotBlank @Size(min = 8, max = 20)
    private String password;
    @NotBlank
    private String name;
    @NotBlank
    private String nickname;
    private String phone;
}
