package com.aewol.domain.account.dto;

import lombok.Builder;
import lombok.Getter;

/** POST /api/accounts/verify-deposit/confirm 응답 */
@Getter
@Builder
public class DepositConfirmResponse {
    private boolean verified;

    // verified=false일 때만 채움: "EXPIRED"(시간 만료) / "MISMATCH"(입금자명 불일치) / "ALREADY_USED"(재사용).
    // 프론트는 아직 이 필드를 안 읽지만(verified만 확인), 디버깅 시 Network 탭 응답에서
    // 바로 원인을 구분할 수 있게 추가함 — 기존 프론트 계약은 그대로 유지됨.
    private String reason;
}
