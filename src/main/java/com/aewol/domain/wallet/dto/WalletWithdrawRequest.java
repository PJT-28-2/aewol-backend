package com.aewol.domain.wallet.dto;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class WalletWithdrawRequest {

    @NotBlank(message = "출금 계좌를 선택해주세요.")
    private String accountId;

    @NotNull(message = "출금 금액을 입력해주세요.")
    @DecimalMin(value = "1", message = "출금 금액은 0보다 커야 합니다.")
    @Digits(integer = 13, fraction = 0, message = "출금 금액은 1원 단위여야 합니다.")
    private BigDecimal amount;

    @Size(max = 200, message = "메모는 200자 이하여야 합니다.")
    private String memo;

    @NotBlank(message = "간편 비밀번호를 입력해주세요.")
    @Pattern(regexp = "\\d{6}", message = "간편 비밀번호는 숫자 6자리여야 합니다.")
    private String password;
}
