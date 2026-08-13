package com.aewol.domain.account.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AccountResponse {
    private String accountId;
    private String bankCode;
    private String bankName;
    // 2026-08-13: 원본 accountNumber 필드를 없애고 마스킹된 값만 내려준다 — 프론트
    // (AccountManagement.vue 등)는 애초에 accountNumberMasked를 기대하고 있었는데
    // 백엔드가 원본 accountNumber를 그대로 내려주고 있어서 필드명이 어긋나 화면에
    // 계좌번호가 아예 안 보이던 문제 + 원본 계좌번호가 응답 바디에 그대로 노출되던
    // 문제를 함께 해결한다(코드리뷰 지적).
    private String accountNumberMasked;
    private Boolean isPrimary;
    private String status;
}
