package com.aewol.domain.grouppurchase.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GroupPurchaseCancelResponse {
    private String gpId;
    private String status;
    private LocalDateTime canceledAt;
    private List<GroupPurchaseCancelParticipantResponse> refundedParticipants;
}
