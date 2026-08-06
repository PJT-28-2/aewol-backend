package com.aewol.domain.account.dto;

import lombok.Builder;
import lombok.Getter;

/** POST /api/accounts/verify-deposit 응답 */
@Getter
@Builder
public class DepositVerificationResponse {
    private String transactionId;

    // CODEF inPrintType=0(4자리 랜덤 숫자)이라 항상 4로 고정된다(2026-08-06). 프론트는
    // 이 값을 직접 읽지 않고 고정 4칸 입력 UI를 쓰지만, CODEF 쪽 값이 흔들리는 상황을
    // 대비해 실제 길이를 계약에 남겨둔다.
    private int depositorNameLength;

    // 테스트 편의용 — 프론트는 안 읽음(계약에 없음). 데모 환경엔 실제 입금 알림이 없어서
    // Network 탭에서 정답을 바로 확인할 수 있게 실어서 보냄.
    private String depositorNameForTest;
}
