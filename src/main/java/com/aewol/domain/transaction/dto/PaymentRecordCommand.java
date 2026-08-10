package com.aewol.domain.transaction.dto;

import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;

/**
 * 지갑 잔액 차감 결제 1건을 원장에 기록하기 위한 커맨드.
 *
 * <p>파라미터를 나열하지 않고 값 객체로 받는 이유: {@code merchantName}/{@code petId}/{@code memo}가
 * 모두 String이라 호출부에서 순서를 바꿔 넣어도 컴파일이 통과하고, 그렇게 되면 메모가 가맹점명으로
 * 기록되는 식으로 조용히 망가진다. 빌더로 이름을 명시하면 이 사고가 구조적으로 불가능해진다.
 */
@Getter
@Builder
public class PaymentRecordCommand {
    private String memberId;
    private String merchantName;
    private BigDecimal amount;
    private String petId;
    private String memo;
}
