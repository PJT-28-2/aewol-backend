package com.aewol.domain.donation.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

/**
 * 기부 내역 항목.
 *
 * <p>MyBatis Map을 그대로 반환하면 응답 키가 DB 컬럼명(snake_case)으로 나가므로
 * DTO로 변환해 camelCase로 통일한다. 내부 식별자(idempotency_key, txn_id 등)는
 * 화면에서 쓰지 않아 노출하지 않는다.
 */
@Getter
@Builder
public class DonationHistoryResponse {
    private final String donationId;
    /** 기부처명. 기부 시점의 표시명 스냅샷(recipient_name)을 우선 사용한다 */
    private final String organization;
    /** 캠페인 제목. 캠페인 없이 단체에 직접 기부한 경우 null */
    private final String campaignTitle;
    private final BigDecimal amount;
    private final String status;
    private final String receiptUrl;
    /** 기부 완료 시각. 미완료 건은 null */
    private final String completedAt;
    private final String createdAt;
}
