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
    /**
     * 지금 추세가 이어지면 어떻게 되는지 한 줄. 전부 자바에서 계산한다.
     *
     * <p>관측 기간이 짧아 추정이 의미 없을 때는 null 로 둔다. 이 값이 없으면
     * 프롬프트에도 넣지 않으므로 모델이 예측을 지어낼 근거 자체가 없다.
     */
    private final String projection;
    private final String ctaLabel;
    private final String ctaPath;
    /** 재료가 그대로면 다시 생성하지 않기 위한 지문. */
    private final String digest;

    /**
     * 모델에게 넘길 사실 묶음. 예측이 있으면 사실과 구분해 덧붙인다.
     *
     * <p>예측도 자바가 계산한 값이라 모델 입장에서는 다른 사실과 다를 바 없다.
     * 다만 라벨을 붙여 두어야 모델이 둘째 문장에 그것을 쓸 수 있다.
     */
    public String promptFacts() {
        if (projection == null || projection.isBlank()) {
            return facts;
        }
        return facts + "\n\n예측(이미 계산된 값이다. 그대로 쓰고 다시 계산하지 마라):\n- " + projection;
    }
}
