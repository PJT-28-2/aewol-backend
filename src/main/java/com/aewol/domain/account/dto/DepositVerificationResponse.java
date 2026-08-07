package com.aewol.domain.account.dto;

import lombok.Builder;
import lombok.Getter;

/** POST /api/accounts/verify-deposit 응답 */
@Getter
@Builder
public class DepositVerificationResponse {
    private String transactionId;

    // CODEF inPrintType=9(고객사 직접 입력)로 우리가 고른 4자 단어를 그대로 쓰기 때문에
    // 길이가 항상 4다(2026-08-06). 프론트(AccountAuthOneWon.vue)는 이 값을 그대로 읽어서
    // 입력 칸 개수를 그린다.
    private int depositorNameLength;

    // 테스트 편의용 — 프론트는 안 읽음(계약에 없음). 데모 환경엔 실제 입금 알림이 없어서
    // Network 탭에서 정답을 바로 확인할 수 있게 실어서 보냄.
    private String depositorNameForTest;
}
