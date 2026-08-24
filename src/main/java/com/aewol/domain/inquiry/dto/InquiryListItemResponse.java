package com.aewol.domain.inquiry.dto;

import lombok.Builder;
import lombok.Getter;

/** GET /api/support/inquiries 목록 항목 */
@Getter
@Builder
public class InquiryListItemResponse {
    private String inquiryId;
    private String inquiryNumber;
    private String category;
    private String title;
    private String status;
    private String createdAt;
}
