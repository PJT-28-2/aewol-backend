package com.aewol.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AccountFindResultResponse {
    private final String provider;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private final String maskedEmail;
}
