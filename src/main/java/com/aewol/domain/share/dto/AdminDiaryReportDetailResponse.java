package com.aewol.domain.share.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AdminDiaryReportDetailResponse {
    private final String reportId;
    private final String diaryId;
    private final String reason;
    private final String status;
    private final String resolution;
    private final String adminNote;
    private final String reporterName;
    private final String reporterEmail;
    private final String authorName;
    private final String petName;
    private final String content;
    private final List<String> images;
    private final String inquiryNumber;
    private final String createdAt;
    private final String resolvedAt;
}
