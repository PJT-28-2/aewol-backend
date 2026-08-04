package com.aewol.domain.share.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ShareInviteDetailResponse {
    private final String accessId;
    private final String petId;
    private final String petName;
    private final String inviterName;
    private final String role;
    private final String status;
    private final String expiresAt;
}
