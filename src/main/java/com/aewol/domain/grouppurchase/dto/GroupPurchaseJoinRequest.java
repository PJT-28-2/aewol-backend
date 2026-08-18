package com.aewol.domain.grouppurchase.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class GroupPurchaseJoinRequest {

    // leave()/cancel()과 동일하게, 실제 지갑 차감이 일어나는 결제 직전에 간편 비밀번호를
    // 재검증한다(2026-08-18) — 기존엔 이 필드가 없어 어떤 비밀번호를 입력해도(또는 아예
    // 안 보내도) 결제가 그대로 진행되는 상태였다.
    @NotBlank(message = "간편 비밀번호를 입력해주세요.")
    @Pattern(regexp = "\\d{6}", message = "간편 비밀번호는 숫자 6자리여야 합니다.")
    private String password;

    @NotBlank
    @Size(max = 30)
    private String recipientName;

    @NotBlank
    @Size(max = 20)
    private String recipientPhone;

    @NotBlank
    @Size(max = 10)
    private String zipCode;

    @NotBlank
    @Size(max = 300)
    private String address;

    @NotBlank
    @Size(max = 100)
    private String addressDetail;
}
