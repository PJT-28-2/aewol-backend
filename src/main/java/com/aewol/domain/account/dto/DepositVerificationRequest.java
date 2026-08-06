package com.aewol.domain.account.dto;

import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** POST /api/accounts/verify-deposit 요청 */
@Getter
@NoArgsConstructor
public class DepositVerificationRequest {
    @NotBlank
    private String bankCode;
    @NotBlank
    private String accountNumber;
}
