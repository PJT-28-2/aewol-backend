package com.aewol.domain.grouppurchase.dto;

/**
 * 공동구매 게시글 카테고리 값의 단일 출처. {@link GroupPurchaseCreateRequest}의 @Pattern 검증과
 * GroupPurchaseServiceImpl/GroupPurchaseRefundExecutor의 toTxnCategory(거래내역 카테고리 매핑)가
 * 이 문자열들을 각자 하드코딩하고 있던 걸 하나로 모았다 — 새 카테고리 추가/오타 시 한 곳만 고치면 된다.
 */
public final class GroupPurchaseCategory {

    public static final String FOOD = "사료";
    public static final String SNACK = "간식";
    public static final String SUPPLY = "용품";
    public static final String ETC = "기타";

    public static final String PATTERN = FOOD + "|" + SNACK + "|" + SUPPLY + "|" + ETC;

    public static final String INVALID_MESSAGE =
            "카테고리는 " + FOOD + ", " + SNACK + ", " + SUPPLY + ", " + ETC + " 중 하나여야 합니다.";

    private GroupPurchaseCategory() {
    }
}
