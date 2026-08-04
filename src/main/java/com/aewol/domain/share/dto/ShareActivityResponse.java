package com.aewol.domain.share.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ShareActivityResponse {
    private final String id;
    private final String title;
    private final String description;
    private final String createdAt;
}
