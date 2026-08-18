package com.aewol.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class KakaoSignupCompleteRequest {

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_-]{43}")
    private String registrationToken;

    @NotBlank
    @Size(max = 10)
    private String zipCode;

    @NotBlank
    @Size(max = 300)
    private String address;

    @Size(max = 100)
    private String addressDetail;

    @AssertTrue
    private boolean terms;

    @AssertTrue
    private boolean privacy;

    private boolean marketing;

    @JsonAnySetter
    private void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("지원하지 않는 요청 필드입니다.");
    }
}
