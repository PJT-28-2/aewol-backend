package com.aewol.domain.share.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SharePetResponse {
    private final String id;
    private final String name;
    private final String type;
    private final String species;
}
