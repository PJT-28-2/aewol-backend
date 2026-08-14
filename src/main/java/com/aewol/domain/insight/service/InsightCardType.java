package com.aewol.domain.insight.service;

/** 홈 인사이트 카드 종류. DB {@code home_insight.card_type} 에 그대로 저장된다. */
public enum InsightCardType {

    /** 정부24 동기화 지원사업 중 조건이 맞는 건 (RF-PA) */
    SUPPORT,
    /** 이번 달 지출 흐름 (코어 지갑) */
    SPENDING,
    /** 공동육아 참여 기록 (RF-CO) */
    CARE,
    /** 짜투리지갑 적립·기부 (RF-SI) */
    DONATION,
}
