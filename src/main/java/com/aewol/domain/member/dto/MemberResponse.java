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
    // 간편 비밀번호(PIN) 설정 여부 — 값 자체는 절대 내려주지 않고 "설정된 적이 있는지"만
    // 알려준다. 프론트가 이 필드 없이 localStorage(hasSimplePassword)만으로 판단하면,
    // 새 기기/브라우저에서 로그인했을 때 서버엔 이미 PIN이 있는데 프론트는 없는 걸로
    // 착각해서 계좌 연동 흐름이 막히던 문제가 있었다(2026-08-13 코드리뷰 지적).
    private Boolean hasSimplePassword;
}
