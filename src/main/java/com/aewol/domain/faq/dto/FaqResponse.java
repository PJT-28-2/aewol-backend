package com.aewol.domain.faq.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * GET /api/support/faqs 목록 항목.
 * api_명세서.md 예시는 question만 쓰고, 상세 예시는 title/content를 쓰는데 실제 DB
 * 컬럼은 V4에서 question/answer로 통일했다 — 목록/상세 다 question/answer로 맞춘다
 * (명세서와 DB가 어긋나면 최신 DB 스키마를 우선한다).
 */
@Getter
@Builder
public class FaqResponse {
    private String faqId;
    private String category;
    private String question;
}
