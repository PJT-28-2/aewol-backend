package com.aewol.domain.member.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** POST /api/members/simple-password 요청 — 송금/이체 확인용 6자리 숫자 PIN */
@Getter
@NoArgsConstructor
public class SimplePasswordRequest {
    @NotBlank
    @Pattern(regexp = "\\d{6}", message = "간편 비밀번호는 숫자 6자리여야 합니다.")
    private String password;
}
