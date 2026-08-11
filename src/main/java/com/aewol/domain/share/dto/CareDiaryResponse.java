package com.aewol.domain.share.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CareDiaryResponse {
    private final String id;
    private final String petId;
    private final String diaryDate;
    private final String content;
    private final List<String> images;
    private final String authorId;
    private final String authorName;
    private final String createdAt;
    /** 요청자가 이 일기를 수정할 수 있는지 (작성자 본인) */
    private final boolean editable;
    /** 요청자가 이 일기를 삭제할 수 있는지 (작성자 본인 또는 반려동물 소유자) */
    private final boolean deletable;
}
