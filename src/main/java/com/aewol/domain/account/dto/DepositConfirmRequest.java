package com.aewol.domain.account.dto;

import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** POST /api/accounts/verify-deposit/confirm 요청 */
@Getter
@NoArgsConstructor
public class DepositConfirmRequest {
    @NotBlank
    private String transactionId;
    @NotBlank
    private String verificationCode;
}
