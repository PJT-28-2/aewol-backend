package com.aewol.domain.share.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AdminDiaryReportListResponse {
    private final List<AdminDiaryReportListItemResponse> reports;
    private final int page;
    private final int size;
    private final boolean hasNext;
}
