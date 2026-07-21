package com.aewol.domain.member.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MemberResponse {
    private String memberId;
    private String email;
    private String nickname;
    private String name;
    private String phone;
    private String profileImg;
    private String provider;
    private String region;
    private String incomeLevel;
}
