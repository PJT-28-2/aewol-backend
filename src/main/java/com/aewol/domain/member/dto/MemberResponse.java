package com.aewol.domain.member.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MemberResponse {
    private String memberId;
    private String email;
    private String name;
    private String phone;
    private String profileImg;
    private String provider;
    private String zipCode;
    private String address;
    private String addressDetail;
}
