package com.aewol.domain.share.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CareDiaryReportRequest {

    /** 사유는 목록에서 고르게 한다. 자유 입력은 그 자체가 또 다른 신고 대상이 될 수 있다. */
    @NotBlank(message = "신고 사유를 골라 주세요.")
    @Pattern(regexp = "SPAM|ABUSE|SEXUAL|PRIVACY|ETC",
            message = "신고 사유가 올바르지 않습니다.")
    private String reason;
}
