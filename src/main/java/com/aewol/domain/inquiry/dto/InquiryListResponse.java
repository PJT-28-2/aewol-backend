package com.aewol.domain.inquiry.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/** GET /api/support/inquiries 응답. 필드명 inquiries는 api_명세서.md 예시를 그대로 따른다. */
@Getter
@Builder
public class InquiryListResponse {
    private List<InquiryListItemResponse> inquiries;
    private boolean hasNext;
}
