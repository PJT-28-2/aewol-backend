package com.aewol.domain.transaction.dto;

import java.math.BigDecimal;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class TossPaymentConfirmRequest {
    @NotBlank
    private String paymentKey;

    // Toss 공식 문서 기준 orderId는 6~64자다. 상한만 두면 6자 미만이 검증을 통과한 뒤
    // Toss가 거절해, 우리 쪽에서 400으로 걸러낼 수 있는 것을 외부 왕복 후에야 알게 된다.
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9-]+$")
    @Size(min = 6, max = 64)
    private String orderId;

    // price 컬럼이 DECIMAL(15,2)이므로 정수부 상한은 13이어야 한다(V1__init_schema.sql:218).
    // 15를 허용하면 검증은 통과하고 Toss 승인 이후 INSERT가 SQL 1264로 실패해 불필요하게
    // 보상(cancel) 경로를 태우게 된다.
    @NotNull
    @Digits(integer = 13, fraction = 0)
    private BigDecimal amount;

    @NotBlank
    private String merchantName;

    private String petId;
    private String memo;
}
