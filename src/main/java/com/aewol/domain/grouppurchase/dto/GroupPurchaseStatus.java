package com.aewol.domain.grouppurchase.dto;

/**
 * 공동구매 게시글 상태 값의 단일 출처. OPEN/CANCELLED만 group_purchase.status 컬럼에 저장되고,
 * COMPLETED/FAILED는 GroupPurchaseServiceImpl#computeStatus가 조회 시점마다 계산해서 내려주는
 * 값이지만, 응답 필드에 쓰는 문자열은 동일하므로 하나의 상수 세트로 관리한다.
 */
public final class GroupPurchaseStatus {

    public static final String OPEN = "OPEN";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private GroupPurchaseStatus() {
    }
}
