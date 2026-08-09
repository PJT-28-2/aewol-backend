package com.aewol.domain.transaction.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class TransactionTagUpdateRequest {
    @NotBlank(message = "카테고리는 필수입니다.")
    @Pattern(regexp = "HOSPITAL|FOOD|GROOMING|TOY|ETC",
            message = "지원하지 않는 카테고리입니다.")
    private String category;
    private String petId;
}
