package com.aewol.domain.member.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SimplePasswordVerifyResponse {
    private final boolean verified;
}
