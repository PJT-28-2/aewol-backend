package com.aewol.domain.inquiry.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/** GET /api/support/inquiries/{inquiryId} 응답 */
@Getter
@Builder
public class InquiryDetailResponse {
    private String inquiryId;
    private String inquiryNumber;
    private String category;
    private String title;
    private String content;
    private String replyEmail;
    private List<String> attachments;
    private String status;
    private String answer;
    private String createdAt;
    private String answeredAt;
}
