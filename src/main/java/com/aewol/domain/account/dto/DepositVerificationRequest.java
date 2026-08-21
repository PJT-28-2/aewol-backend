package com.aewol.domain.account.dto;

import javax.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** POST /api/accounts/verify-deposit 요청 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class DepositVerificationRequest {
    @NotBlank
    private String bankCode;
    @NotBlank
    private String accountNumber;
}
