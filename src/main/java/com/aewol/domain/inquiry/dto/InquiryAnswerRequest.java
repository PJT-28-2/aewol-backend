package com.aewol.domain.inquiry.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class InquiryAnswerRequest {

    @NotBlank(message = "답변을 입력해주세요")
    @Size(max = 5000, message = "답변은 5000자 이하로 입력해주세요")
    private String answer;
}
