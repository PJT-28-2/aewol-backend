package com.aewol.domain.share.dto;

import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ShareRoleUpdateRequest {

    @NotBlank(message = "반려동물을 선택해 주세요.")
    private String petId;

    @NotBlank(message = "권한을 선택해 주세요.")
    private String role;
}
