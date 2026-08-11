package com.aewol.domain.wallet.dto;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Toss 결제창에서 카드 결제를 마친 뒤, successUrl로 받은 값으로 지갑 충전을 요청하는 DTO.
 *
 * <p>Toss는 "결제"를 처리하지만 그 결과로 우리 시스템에서 일어나는 일은 <b>지갑 충전</b>이다.
 * 사용자는 카드에서 한 번만 돈을 내고, 그 금액이 애월 지갑 잔액으로 들어온다.
 * 실제 상품 결제는 이 지갑 잔액에서 차감된다({@code POST /api/transactions/payment}).
 */
@Getter
@NoArgsConstructor
public class TossChargeRequest {

    @NotBlank
    private String paymentKey;

    // Toss 공식 문서 기준 orderId는 6~64자다. 상한만 두면 6자 미만이 검증을 통과한 뒤
    // Toss가 거절해, 우리 쪽에서 400으로 걸러낼 수 있는 것을 외부 왕복 후에야 알게 된다.
    // Redis 클레임 키의 재료이기도 해서 형식과 길이를 반드시 제한한다.
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9-]+$")
    @Size(min = 6, max = 64)
    private String orderId;

    // price 컬럼이 DECIMAL(15,2)이므로 정수부 상한은 13이어야 한다(V1__init_schema.sql:218).
    // 15를 허용하면 검증은 통과하고 Toss 승인 이후 INSERT가 SQL 1264로 실패해
    // 불필요하게 보상(cancel) 경로를 태우게 된다.
    @NotNull
    @DecimalMin(value = "1", message = "충전 금액은 0보다 커야 합니다.")
    @Digits(integer = 13, fraction = 0)
    private BigDecimal amount;
}
