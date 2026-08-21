package com.aewol.domain.insight.dto;

import com.aewol.domain.grouppurchase.dto.GroupPurchaseListItemResponse;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/** 홈 화면 지출 카드 아래에 놓이는 AI 인사이트 카드 한 장. */
@Getter
@Builder
public class HomeInsightResponse {

    /** SUPPORT / SPENDING / CARE / DONATION */
    private final String type;
    /** 숫자가 들어가는 제목. LLM이 아니라 데이터로 직접 만든다(틀리면 안 되는 값이라서). */
    private final String headline;
    /** LLM이 생성한 2문장. 실패하면 데이터로 만든 대체 문구가 들어간다. */
    private final String body;
    private final String ctaLabel;
    private final String ctaPath;
    /**
     * 앞으로 어떻게 될지 한 줄. 헤드라인과 마찬가지로 자바에서 계산해 매번 새로 만든다.
     *
     * <p>근거가 부족하면(관측 기간이 짧으면) null 이다. 없는 값을 억지로 채우지 않는다.
     */
    private final String projection;
    /** 문구가 LLM 없이 만들어졌는지. 프론트가 표시를 달리하고 싶을 때 쓴다. */
    private final boolean fallback;
    private final String generatedAt;
    /**
     * 이 카드와 관련해 추천하는 공동구매 상품(최대 3건). 현재는 SPENDING 카드에서
     * 이번 달 가장 많이 쓴 카테고리 기준으로만 채운다. 맞는 카테고리가 없으면 비어 있다.
     */
    private final List<GroupPurchaseListItemResponse> recommendedProducts;
    /** 카테고리별 비중(도넛 차트용). SPENDING 카드에서만 채운다. */
    private final List<CategoryShare> categoryBreakdown;
}
