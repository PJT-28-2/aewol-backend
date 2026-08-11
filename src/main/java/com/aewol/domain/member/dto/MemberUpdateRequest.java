package com.aewol.domain.member.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import javax.validation.constraints.Size;

@Getter
@NoArgsConstructor
public class MemberUpdateRequest {
    @Size(max = 20)
    private String phone;
    @Size(max = 500)
    private String profileImg;
    @Size(max = 10)
    private String zipCode;
    @Size(max = 300)
    private String address;
    @Size(max = 100)
    private String addressDetail;
}
