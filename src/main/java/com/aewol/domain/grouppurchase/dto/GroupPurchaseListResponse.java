package com.aewol.domain.grouppurchase.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GroupPurchaseListResponse {
    private List<GroupPurchaseListItemResponse> items;
    private boolean hasNext;
    // hasNext=true일 때만 값이 있다. 다음 요청의 cursor 파라미터에 그대로 넣어 보내면 된다 —
    // 정렬 키가 그대로 노출되지 않는 불투명(opaque) 토큰이라 프론트가 내부 구조를 몰라도 된다.
    private String nextCursor;
}
