package com.aewol.external.tosspayments;

import java.util.Map;

/**
 * {@link TossPaymentsClient#confirmPayment(String, String, long)}의 반환 타입.
 * 호출자가 {@link TossConfirmOutcome}으로 다섯 분류를 분기하고, 필요하면
 * {@link #getTotalAmount()}(승인 성공 시) 또는 {@link #getCode()}/{@link #getMessage()}
 * (Toss 원본 에러 코드/메시지, 감사 로그용)를 참조할 수 있다.
 */
public class TossConfirmResult {

    private final TossConfirmOutcome outcome;
    private final String code;
    private final String message;
    private final Long totalAmount;
    private final Map<String, Object> rawBody;

    private TossConfirmResult(TossConfirmOutcome outcome, String code, String message,
                               Long totalAmount, Map<String, Object> rawBody) {
        this.outcome = outcome;
        this.code = code;
        this.message = message;
        this.totalAmount = totalAmount;
        this.rawBody = rawBody;
    }

    /**
     * 승인 성공(HTTP 200 && status == "DONE"). 원장 차감 기준 금액은 요청 DTO의
     * amount가 아니라 여기서 반환하는 totalAmount를 써야 한다 — suppliedAmount는
     * 부가세 제외분이라 차감액이 적어진다.
     */
    public static TossConfirmResult success(long totalAmount, Map<String, Object> rawBody) {
        return new TossConfirmResult(TossConfirmOutcome.SUCCESS, null, null, totalAmount, rawBody);
    }

    /** SUCCESS 이외의 네 가지 결과(ALREADY_APPROVED / DEFINITIVE_REJECTION / CONFIG_ERROR / INDETERMINATE). */
    public static TossConfirmResult of(TossConfirmOutcome outcome, String code, String message,
                                        Map<String, Object> rawBody) {
        if (outcome == TossConfirmOutcome.SUCCESS) {
            throw new IllegalArgumentException("SUCCESS는 success(totalAmount, rawBody)로 생성해야 합니다.");
        }
        return new TossConfirmResult(outcome, code, message, null, rawBody);
    }

    public TossConfirmOutcome getOutcome() {
        return outcome;
    }

    /** Toss 원본 에러 코드. SUCCESS이거나 본문 파싱에 실패한 경우 null. */
    public String getCode() {
        return code;
    }

    /** Toss 원본 에러 메시지 또는 예외 메시지(감사 로그용). */
    public String getMessage() {
        return message;
    }

    /** SUCCESS일 때만 채워진다 — 원장 차감 기준 금액(Toss confirm 응답의 totalAmount). */
    public Long getTotalAmount() {
        return totalAmount;
    }

    /** 파싱에 성공한 Toss 응답 원본(있는 경우). */
    public Map<String, Object> getRawBody() {
        return rawBody;
    }
}
