package com.aewol.domain.share.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AdminDiaryReportListItemResponse {
    private final String reportId;
    private final String diaryId;
    private final String reason;
    private final String status;
    private final String resolution;
    private final String reporterName;
    private final String authorName;
    private final String petName;
    private final String contentPreview;
    private final String createdAt;
    private final String resolvedAt;
}
