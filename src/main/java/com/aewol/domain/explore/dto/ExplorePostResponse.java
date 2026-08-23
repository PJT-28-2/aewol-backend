package com.aewol.domain.explore.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 탐색 그리드에 놓이는 공개 일기 한 장.
 *
 * <p>계정 주체가 반려동물이라 <b>사람 정보를 담지 않는다</b>. 작성자 이름·회원 id를 넣지
 * 않는 것이 이 설계의 핵심 이점이고, 여기에 넣는 순간 그 이점이 사라진다.
 */
@Getter
@Builder
public class ExplorePostResponse {

    private final String diaryId;
    private final String petId;
    private final String petName;
    private final String imageUrl;
    private final String content;
    private final String diaryDate;
    private final String createdAt;
}
