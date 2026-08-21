package com.aewol.domain.share.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ShareInviteResponse {
    private final String accessId;
    private final String inviteCode;
    /** 링크 만료 시각. 화면이 남은 시간을 세어 보여주는 데 쓴다. */
    private final String expiresAt;
}
