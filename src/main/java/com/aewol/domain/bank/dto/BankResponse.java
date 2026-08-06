package com.aewol.domain.bank.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BankResponse {
    private String bankCode;
    private String bankName;
}
