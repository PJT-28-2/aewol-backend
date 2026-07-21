package com.aewol.domain.member.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MemberUpdateRequest {
    private String nickname;
    private String phone;
    private String profileImg;
    private String region;
    private String incomeLevel;
}
