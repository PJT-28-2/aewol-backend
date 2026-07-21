package com.aewol.domain.account.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AccountResponse {
    private String accountId;
    private String bankCode;
    private String bankName;
    private String accountNumber;
    private String accountHolder;
    private Boolean isPrimary;
    private String status;
}
