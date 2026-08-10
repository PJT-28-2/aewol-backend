package com.aewol.domain.member.dto;

import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MemberPasswordVerifyRequest {

    @NotBlank
    private String currentPassword;
}
