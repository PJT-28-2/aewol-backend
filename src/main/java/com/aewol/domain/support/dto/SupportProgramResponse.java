package com.aewol.domain.support.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SupportProgramResponse {
    private final String id;
    private final String title;
    private final String summary;
    private final String agency;
    private final String benefit;
    private final String period;
    private final String applyUrl;
    private final boolean eligible;
    private final boolean applied;
    private final List<SupportConditionResponse> conditions;
}
