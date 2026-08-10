package com.aewol.domain.wallet.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

/**
 * 외부 PG 승인으로 확정된 지갑 충전 1건을 원장에 기록하기 위한 커맨드.
 *
 * <p>파라미터를 나열하지 않고 값 객체로 받는 이유: {@code memberId}/{@code paymentKey}/
 * {@code orderId}가 모두 String이라 호출부에서 순서를 바꿔 넣어도 컴파일이 통과한다.
 * 그렇게 되면 사용자 식별자가 UNIQUE 제약이 걸린 {@code payment_key} 컬럼에 기록되는 등
 * 조용히 망가진다. 빌더로 이름을 명시하면 이 사고가 구조적으로 불가능해진다.
 */
@Getter
@Builder
public class ExternalChargeCommand {

    private String memberId;

    /** PG가 확정한 금액. 클라이언트가 보낸 값이 아니라 승인 응답의 금액을 쓴다. */
    private BigDecimal amount;

    /** PG 결제 식별자. transaction.payment_key(UNIQUE)에 기록되어 중복 적립을 막는다. */
    private String paymentKey;

    /** 주문번호. 감사 로그와 원장을 역방향으로 대사할 때 쓴다. */
    private String orderId;
}
