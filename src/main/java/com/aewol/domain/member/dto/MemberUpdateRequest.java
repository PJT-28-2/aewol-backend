package com.aewol.domain.member.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MemberUpdateRequest {
    private String name;
    private String phone;
    private String profileImg;
    private String zipCode;
    private String address;
    private String addressDetail;
}
