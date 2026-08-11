package com.aewol.external.tosspayments;

/**
 * {@link TossPaymentsClient#cancelPayment(String, String)}(보상 호출)의 반환 타입.
 * cancelPayment는 실패해도 예외를 밖으로 던지지 않는다 — 원래 실패(원장 기록 실패 등)를
 * 처리하던 호출자가 이 결과로 AUDIT_COMPENSATED / AUDIT_COMPENSATION_FAILED를
 * 분기해서 기록한다.
 */
public class TossCancelResult {

    private final boolean success;
    private final boolean alreadyCanceled;
    private final String code;
    private final String message;

    private TossCancelResult(boolean success, boolean alreadyCanceled, String code, String message) {
        this.success = success;
        this.alreadyCanceled = alreadyCanceled;
        this.code = code;
        this.message = message;
    }

    public static TossCancelResult success() {
        return new TossCancelResult(true, false, null, null);
    }

    /**
     * Toss가 ALREADY_CANCELED_PAYMENT를 반환한 경우 — 목표(취소된 상태)가 이미
     * 달성되어 있으므로 보상 성공으로 흡수한다(.omc/research/toss-api-spec.md §3.3 권고).
     */
    public static TossCancelResult alreadyCanceled(String code, String message) {
        return new TossCancelResult(true, true, code, message);
    }

    public static TossCancelResult failure(String code, String message) {
        return new TossCancelResult(false, false, code, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isAlreadyCanceled() {
        return alreadyCanceled;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
