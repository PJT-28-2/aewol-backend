package com.aewol.domain.inquiry.dto;

import lombok.Builder;
import lombok.Getter;

/** POST /api/support/inquiries 응답 */
@Getter
@Builder
public class InquiryCreateResponse {
    private String inquiryId;
    private String inquiryNumber;
}
