package com.aewol.domain.share.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ShareLinkInviteRequest {

    @NotBlank(message = "반려동물을 선택해 주세요.")
    private String petId;

    private String role = "VIEWER";

    /**
     * 링크가 살아 있는 시간(분).
     *
     * <p>받는 사람을 지정하지 않는 대신 시간으로 위험을 줄이는 방식이라, 이 값이
     * 이 초대의 유일한 방어선이다. 그래서 상한을 하루로 막아 둔다. 그보다 길게
     * 열어두려면 링크가 아닌 다른 방식이어야 한다.
     *
     * <p>보내지 않으면 {@code 10}분으로 본다.
     */
    @Min(value = 1, message = "유효시간은 1분 이상이어야 합니다.")
    @Max(value = 1440, message = "유효시간은 24시간을 넘을 수 없습니다.")
    private Integer expiresInMinutes;
}
