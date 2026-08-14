package com.aewol.domain.insight.service;

/**
 * 카드 한 종류의 재료를 모은다.
 *
 * <p>구현체는 자기 도메인 서비스만 호출하고 LLM 은 건드리지 않는다. 생성·캐시·실패
 * 처리는 {@link HomeInsightServiceImpl} 이 공통으로 맡는다.
 */
public interface InsightCardCollector {

    InsightCardType type();

    /**
     * 보여줄 것이 없으면 {@code null} 을 돌려준다. 빈 카드를 띄우느니 카드를 아예
     * 내보내지 않는 편이 낫다.
     */
    InsightCard collect(String memberId, String petId);
}
