package com.aewol.domain.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 충전 주문 생성 응답 DTO.
 *
 * <p>클라이언트는 {@code orderId}로 Toss SDK를 초기화하고, 결제 완료 후
 * {@code POST /api/wallet/toss-charge}에 {@code paymentKey}와 함께 전달한다.
 */
@Getter
@RequiredArgsConstructor
public class TossChargeOrderResponse {

    @JsonProperty("orderId")
    private final String orderId;

    @JsonProperty("amount")
    private final BigDecimal amount;
}
