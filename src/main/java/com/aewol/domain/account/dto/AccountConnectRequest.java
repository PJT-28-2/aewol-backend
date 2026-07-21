package com.aewol.domain.account.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AccountConnectRequest {
    @NotBlank
    private String bankCode;
    @NotBlank
    private String bankName;
    @NotBlank
    private String accountNumber;
    @NotBlank
    private String accountHolder;
    private Boolean isPrimary;
}
