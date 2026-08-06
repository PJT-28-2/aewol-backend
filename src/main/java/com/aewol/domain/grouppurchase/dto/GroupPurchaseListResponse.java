package com.aewol.domain.grouppurchase.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GroupPurchaseListResponse {
    private List<GroupPurchaseListItemResponse> items;
    private boolean hasNext;
}
