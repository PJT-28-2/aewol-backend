package com.aewol.domain.share.dto;

import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ShareInviteRequest {

    @NotBlank(message = "반려동물을 선택해 주세요.")
    private String petId;

    @NotBlank(message = "이메일 또는 휴대전화 번호를 입력해 주세요.")
    private String recipient;

    private String role = "VIEWER";
}
