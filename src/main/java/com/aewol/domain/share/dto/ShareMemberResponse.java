package com.aewol.domain.share.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ShareMemberResponse {
    private final String id;
    private final String name;
    private final String role;
    private final String status;
}
