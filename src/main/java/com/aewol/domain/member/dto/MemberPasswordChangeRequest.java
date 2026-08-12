package com.aewol.domain.member.dto;

import com.aewol.common.validation.ValidPassword;
import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MemberPasswordChangeRequest {

    @NotBlank
    private String currentPassword;

    @NotBlank
    @ValidPassword
    private String newPassword;
}
