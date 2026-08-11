package com.aewol.external.tosspayments;

/**
 * {@link TossPaymentsClient#confirmPayment(String, String, long)} 호출자가 반드시
 * 구분해야 하는 다섯 가지 결과. 분류는 HTTP 상태코드가 아니라 Toss 응답 본문의
 * {@code code} 문자열을 기준으로 한다 — 상태코드만으로는 {@code INVALID_API_KEY}(400,
 * 설정 오류)와 {@code REJECT_CARD_COMPANY}(403, 카드 거절)를 구분할 수 없다
 * (.omc/research/toss-api-spec.md §3.1 참고).
 */
public enum TossConfirmOutcome {

    /** HTTP 200 이고 응답 status가 "DONE" — 실제로 승인 완료됨. */
    SUCCESS,

    /** 이전 confirm 호출이 이미 성공했음을 의미(ALREADY_PROCESSED_PAYMENT, DUPLICATED_REQUEST).
     * Toss가 승인을 보유하고 있으므로 클레임을 유지해야 한다. */
    ALREADY_APPROVED,

    /** 카드/계좌/요청 자체가 확정적으로 거절됨 — Toss가 승인을 보유하지 않는다. */
    DEFINITIVE_REJECTION,

    /** 시크릿/클라이언트 키 또는 인증 헤더 설정이 잘못됨 — Toss가 요청을 인증조차
     * 하지 않았으므로 승인을 보유할 수 없다. */
    CONFIG_ERROR,

    /** 승인 여부를 알 수 없음(타임아웃, 5xx, 파싱 실패, 미열거 code 등) — 안전한
     * 기본값. Toss가 승인을 보유하고 있을 가능성이 조금이라도 있으면 이 값을 쓴다. */
    INDETERMINATE
}
