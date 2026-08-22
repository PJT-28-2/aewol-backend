package com.aewol.domain.share.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CareDiaryVisibilityRequest {

    /** PRIVATE(가족만) 또는 PUBLIC(멍스타그램 공개). */
    @NotBlank(message = "공개 여부를 지정해 주세요.")
    @Pattern(regexp = "PRIVATE|PUBLIC", message = "공개 여부는 PRIVATE 또는 PUBLIC이어야 합니다.")
    private String visibility;
}
