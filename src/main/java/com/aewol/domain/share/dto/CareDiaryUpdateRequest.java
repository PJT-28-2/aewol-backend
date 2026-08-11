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
}
