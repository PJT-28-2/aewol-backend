package com.aewol.domain.faq.dto;

import lombok.Builder;
import lombok.Getter;

/** GET /api/support/faqs/{faqId} 상세 응답. */
@Getter
@Builder
public class FaqDetailResponse {
    private String faqId;
    private String category;
    private String question;
    private String answer;
}
