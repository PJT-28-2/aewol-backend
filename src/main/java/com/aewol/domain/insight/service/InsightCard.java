package com.aewol.domain.insight.service;

import lombok.Builder;
import lombok.Getter;

/**
 * 카드 하나를 만들기 위해 수집한 재료.
 *
 * <p>{@code headline} 과 {@code fallbackBody} 는 데이터로 직접 만든다. 숫자가 틀리면
 * 안 되는 값이고, 외부 호출이 실패해도 카드는 떠야 하기 때문이다. LLM 은 {@code facts}
 * 를 받아 {@code fallbackBody} 를 대체할 문장만 쓴다.
 */
@Getter
@Builder
public class InsightCard {

    private final InsightCardType type;
    private final String headline;
    /** 프롬프트에 넣을 사실 목록. 여기 없는 내용은 모델이 지어내면 안 된다. */
    private final String facts;
    /** LLM 호출이 실패하거나 꺼져 있을 때 쓰는 문구. */
    private final String fallbackBody;
    private final String ctaLabel;
    private final String ctaPath;
    /** 재료가 그대로면 다시 생성하지 않기 위한 지문. */
    private final String digest;
}
