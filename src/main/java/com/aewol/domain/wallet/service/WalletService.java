package com.aewol.domain.wallet.service;

import com.aewol.domain.wallet.dto.ExternalChargeCommand;
import com.aewol.domain.wallet.dto.WalletResponse;
import java.math.BigDecimal;

public interface WalletService {
    WalletResponse getWallet(String memberId);

    WalletResponse deposit(String memberId, BigDecimal amount);

    /**
     * 외부 PG 승인이 확정된 충전을 원장에 기록한다.
     *
     * <p><b>인터페이스에 두는 이유</b>: 오케스트레이터({@code TossChargeService})는 PG HTTP 호출을
     * 트랜잭션 밖에서 하기 위해 비트랜잭셔널 빈으로 분리되어 있다. 이 메서드가 프록시 경계를 넘어
     * 호출되어야만 {@code @Transactional}이 적용되므로, private 헬퍼로 내리면 안 된다.
     */
    WalletResponse depositExternal(ExternalChargeCommand command);
}
