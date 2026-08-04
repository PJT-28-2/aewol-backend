package com.aewol.domain.support.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SupportProgramsResponse {
    private final String petId;
    private final String petName;
    private final List<SupportProgramResponse> programs;
}
