package com.aewol.domain.member.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MemberPasswordChangeRequest {

    @NotBlank
    private String currentPassword;

    @NotBlank
    @Size(min = 8, max = 20)
    private String newPassword;
}
