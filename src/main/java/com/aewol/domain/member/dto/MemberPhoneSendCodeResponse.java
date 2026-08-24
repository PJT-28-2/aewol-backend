package com.aewol.domain.member.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MemberPhoneSendCodeResponse {
    private final long expiresInSeconds;
}
