package com.aewol.external.sms;

/**
 * SMS 발송 실패 원인. 사용자 응답은 503으로 유지하고, 로그와 예외로만 구분한다.
 */
public enum SmsFailureReason {
    /** 키·시크릿·발신번호가 비어 HTTP 호출 전에 실패 */
    NOT_CONFIGURED,
    /** SOLAPI가 401/403으로 거절. 키 오타·권한 문제 */
    AUTH,
    /** 발신번호 미등록·미승인 */
    SENDER_NOT_APPROVED,
    /** 메시지는 전달됐지만 등록 결과를 신뢰할 수 없음 */
    PROVIDER_REJECTED,
    /** 타임아웃·5xx 등 전송 계층 실패 */
    TRANSPORT_OR_HTTP
}
