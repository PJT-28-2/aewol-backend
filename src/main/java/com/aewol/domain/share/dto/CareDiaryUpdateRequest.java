package com.aewol.domain.share.dto;

import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CareDiaryUpdateRequest {

    /** yyyy-MM-dd. 비우면 기존 날짜를 유지한다. */
    private String diaryDate;

    @Size(max = 500, message = "내용은 500자까지 입력할 수 있습니다.")
    private String content;

    /**
     * 화면이 불러올 때 받은 버전. 그대로 돌려보내면 그 사이 다른 곳에서 저장된 경우
     * 409로 거절된다.
     *
     * <p>비워도 된다. 아직 이 값을 보내지 않는 클라이언트를 위해 남겨둔 것이고, 그때는
     * 예전처럼 나중 저장이 이긴다.
     */
    private Long version;
}
