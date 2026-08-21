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
    /**
     * 낙관락 버전. 수정 요청에 이 값을 그대로 실어 보내면 그 사이 다른 곳에서 저장된
     * 경우 409로 거절된다. 보내지 않으면 검사 없이 덮어쓴다.
     */
    private final Long version;
    /** PRIVATE(가족만) / PUBLIC(멍스타그램 공개) */
    private final String visibility;
    /**
     * 신고로 노출이 멈춘 일기인지. 이 경우 작성자도 다시 공개할 수 없고 관리자만 되돌린다.
     * 화면은 공개 전환 스위치를 잠그고 사유를 안내해야 한다.
     */
    private final boolean hiddenByReport;
    /** 요청자가 이 일기를 수정할 수 있는지 (작성자 본인) */
    private final boolean editable;
    /** 요청자가 이 일기를 삭제할 수 있는지 (작성자 본인 또는 반려동물 소유자) */
    private final boolean deletable;
}
