package com.aewol.domain.account.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * PATCH /api/accounts/{accountId} 요청 — 대표 계좌 설정.
 * isPrimary=false로 대표 계좌를 "해제"하는 동작은 지원하지 않는다(회원당 대표 계좌가
 * 0개가 되는 상태를 허용하지 않기 위해) — 다른 계좌를 대표로 지정하면 자동으로 해제된다.
 * true가 아닌 값이 오면 서비스 레이어에서 BusinessException으로 막는다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AccountPrimaryRequest {
    private Boolean isPrimary;
}
