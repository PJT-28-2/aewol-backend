package com.aewol.domain.explore.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/** 거래내역 목록과 같은 커서 페이징 규약을 따른다. */
@Getter
@Builder
public class ExplorePageResponse {
    private final List<ExplorePostResponse> posts;
    /** 다음 장이 없으면 null. */
    private final String nextCursor;
}
