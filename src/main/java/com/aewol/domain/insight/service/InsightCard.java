package com.aewol.domain.insight.service;

import com.aewol.domain.grouppurchase.dto.GroupPurchaseListItemResponse;
import com.aewol.domain.insight.dto.CategoryShare;
import java.util.List;
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
     * <p>관측 기간이 짧아 추정이 의미 없을 때는 null 로 둔다. 본문과 중복되지 않도록
     * LLM 프롬프트에는 넣지 않고 응답의 별도 필드로만 전달한다.
     */
    private final String projection;
    private final String ctaLabel;
    private final String ctaPath;
    /** 재료가 그대로면 다시 생성하지 않기 위한 지문. */
    private final String digest;
    /**
     * 카드 내용과 관련해 함께 보여줄 공동구매 상품. LLM이 아니라 자바에서 직접 고른다.
     *
     * <p>맞는 상품이 없으면 null/빈 리스트로 둔다. 억지로 채우지 않는다.
     */
    private final List<GroupPurchaseListItemResponse> recommendedProducts;
    /**
     * 카테고리별 비중(도넛 차트용). 지출 관련 카드에서만 채운다.
     *
     * <p>비중 상위 항목만 담고 나머지는 '기타'로 합친다 — 자바에서 계산하므로 숫자가
     * 틀리면 안 되고, 본문 문장의 1위 카테고리와 반드시 같은 집계에서 나와야 한다
     * (따로 계산하면 문장과 차트가 다른 1위를 말하는 사고가 날 수 있다).
     */
    private final List<CategoryShare> categoryBreakdown;
}
