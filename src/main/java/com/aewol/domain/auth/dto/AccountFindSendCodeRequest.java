package com.aewol.domain.auth.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AccountFindSendCodeRequest {

    @NotBlank
    @Size(max = 20)
    private String name;

    @NotBlank
    @Pattern(regexp = "^010\\d{8}$")
    private String phone;
}
