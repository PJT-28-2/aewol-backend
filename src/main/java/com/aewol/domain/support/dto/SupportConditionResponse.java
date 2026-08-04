package com.aewol.domain.support.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SupportConditionResponse {
    private final boolean met;
    private final String title;
    private final String description;
}
