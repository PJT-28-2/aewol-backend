package com.aewol.domain.grouppurchase.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** leave()/cancel() 공통 요청 바디. 두 API 모두 처리 직전 간편 비밀번호 재검증에만 이 값을 사용한다. */
@Getter
@NoArgsConstructor
public class GroupPurchasePasswordRequest {

    @NotBlank(message = "간편 비밀번호를 입력해주세요.")
    @Pattern(regexp = "\\d{6}", message = "간편 비밀번호는 숫자 6자리여야 합니다.")
    private String password;
}
