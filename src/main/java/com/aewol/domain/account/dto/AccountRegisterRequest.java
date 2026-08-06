package com.aewol.domain.account.dto;

import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** POST /api/accounts 요청 — 1원 인증(verify-deposit/confirm)이 끝난 transactionId로 계좌 등록을 확정한다 */
@Getter
@NoArgsConstructor
public class AccountRegisterRequest {
    @NotBlank
    private String transactionId;
    private Boolean isPrimary;
}
