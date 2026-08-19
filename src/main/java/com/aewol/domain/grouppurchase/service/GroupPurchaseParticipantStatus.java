package com.aewol.domain.grouppurchase.service;

/** 공동구매 참여자(group_purchase_participant.payment_status) 상태 값의 단일 출처. */
public final class GroupPurchaseParticipantStatus {

    public static final String PENDING = "PENDING";
    public static final String PAID = "PAID";
    public static final String CANCELLED = "CANCELLED";

    private GroupPurchaseParticipantStatus() {
    }
}
