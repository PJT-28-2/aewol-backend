package com.aewol.domain.member.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * POST /api/users/simple-password 요청 — 송금/이체 확인용 6자리 숫자 PIN.
 * currentPassword는 재설정(이미 PIN이 설정된 회원)일 때만 필요하다 — Access Token만으로
 * 기존 PIN을 마음대로 덮어써서 송금 인증을 우회하는 걸 막기 위함(2026-08-12, 리뷰 반영).
 * 최초 설정(기존 PIN이 없는 회원)은 계좌 연동 직후 흐름이라 비워둬도 된다.
 */
@Getter
@NoArgsConstructor
public class SimplePasswordRequest {
    @NotBlank
    @Pattern(regexp = "\\d{6}", message = "간편 비밀번호는 숫자 6자리여야 합니다.")
    private String password;

    private String currentPassword;
}
