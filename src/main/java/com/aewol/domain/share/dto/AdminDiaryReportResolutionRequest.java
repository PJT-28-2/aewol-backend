package com.aewol.domain.share.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AdminDiaryReportResolutionRequest {
    @NotBlank(message = "처리 결과를 선택해 주세요.")
    @Pattern(regexp = "KEEP_HIDDEN|RESTORE", message = "처리 결과가 올바르지 않습니다.")
    private String resolution;

    @Size(max = 500, message = "관리자 메모는 500자 이하여야 합니다.")
    private String adminNote;
}
