package com.aewol.domain.wallet.dto;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Toss 결제창을 열기 전에 서버에서 충전 주문을 생성할 때 쓰는 요청 DTO.
 *
 * <p>클라이언트는 이 금액으로 서버에서 orderId를 발급받고, 그 orderId로 Toss SDK를
 * 초기화해야 한다. 서버가 orderId와 회원·금액을 기록해두기 때문에, 이후 승인 단계에서
 * 요청 회원이 주문의 실제 소유자인지, 금액이 변조되지 않았는지 검증할 수 있다.
 */
@Getter
@NoArgsConstructor
public class TossChargeOrderRequest {

    @NotNull
    @DecimalMin(value = "1", message = "충전 금액은 0보다 커야 합니다.")
    @Digits(integer = 13, fraction = 0)
    private BigDecimal amount;
}
