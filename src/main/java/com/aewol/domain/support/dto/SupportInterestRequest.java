package com.aewol.domain.support.dto;

import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SupportInterestRequest {

    @NotBlank(message = "반려동물을 선택해 주세요.")
    private String petId;
}
