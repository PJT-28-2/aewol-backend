package com.aewol.domain.share.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ShareInviteResponse {
    private final String accessId;
    private final String inviteCode;
}
