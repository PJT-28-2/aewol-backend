package com.aewol.domain.share.dto;

import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ShareLinkInviteRequest {

    @NotBlank(message = "반려동물을 선택해 주세요.")
    private String petId;

    private String role = "VIEWER";
}
