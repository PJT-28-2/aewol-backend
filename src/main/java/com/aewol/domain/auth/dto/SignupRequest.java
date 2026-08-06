package com.aewol.domain.auth.dto;

import javax.validation.constraints.Email;
import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SignupRequest {
    @NotBlank @Email @Size(max = 200)
    private String email;
    @NotBlank @Pattern(regexp = "\\d{6}")
    private String verificationCode;
    @NotBlank @Size(min = 8, max = 20)
    private String password;
    @NotBlank @Size(max = 20)
    private String name;
    @Size(max = 20)
    private String phone;
    @NotBlank @Size(max = 10)
    private String zipCode;
    @NotBlank @Size(max = 300)
    private String address;
    @Size(max = 300)
    private String addressDetail;
    @AssertTrue
    private boolean terms;
    @AssertTrue
    private boolean privacy;
    private boolean marketing;
}
