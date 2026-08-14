package com.aewol.domain.grouppurchase.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class GroupPurchaseCancelRequest {

    @NotBlank(message = "간편 비밀번호를 입력해주세요.")
    @Pattern(regexp = "\\d{6}", message = "간편 비밀번호는 숫자 6자리여야 합니다.")
    private String password;
}
