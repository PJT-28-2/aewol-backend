package com.aewol.domain.auth.dto;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
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
