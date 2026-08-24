package com.aewol.external.tosspayments;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Toss 결제 흐름에서 자동 복구가 불가능한 경로(불확정 승인, 이미 승인됨, 보상 성공/실패)를
 * 남기는 감사 협력자.
 *
 * <p>이 프로젝트는 {@code slf4j-simple}을 쓰고 있어(build.gradle.kts, 팀 합의 전 임시 백엔드라는
 * TODO 주석 존재) Logback의 {@code ListAppender}로 로그 문자열을 검증할 수 없다. 그래서 감사
 * 기록을 로그 문자열이 아니라 타입 있는 협력자 메서드로 분리하고, 테스트는 이 컴포넌트를 모킹해
 * {@code verify()}로 호출 여부를 검증한다.
 */
@Slf4j
@Component
public class TossPaymentAuditLogger {

    private static final String AUDIT_ALREADY_APPROVED = "AUDIT_ALREADY_APPROVED";
    private static final String AUDIT_CONFIRM_INDETERMINATE = "AUDIT_CONFIRM_INDETERMINATE";
    private static final String AUDIT_COMPENSATED = "AUDIT_COMPENSATED";
    private static final String AUDIT_COMPENSATION_FAILED = "AUDIT_COMPENSATION_FAILED";

    /** Toss가 "이미 처리됨"으로 응답한 경우 — 이전 confirm이 성공했을 가능성이 있다. */
    public void alreadyApproved() {
        log.error(AUDIT_ALREADY_APPROVED);
    }

    /** Toss confirm 호출이 타임아웃/5xx/미열거 에러코드 등으로 승인 여부를 확정할 수 없는 경우. */
    public void confirmIndeterminate() {
        log.error(AUDIT_CONFIRM_INDETERMINATE);
    }

    /** 승인 후 원장 기록 실패로 cancel 보상을 호출했고, 그 보상이 성공한 경우. */
    public void compensated() {
        log.error(AUDIT_COMPENSATED);
    }

    /** 승인 후 원장 기록 실패로 cancel 보상을 호출했으나, 그 보상마저 실패한 경우 — 수동 대사 필요. */
    public void compensationFailed() {
        log.error(AUDIT_COMPENSATION_FAILED);
    }
}
