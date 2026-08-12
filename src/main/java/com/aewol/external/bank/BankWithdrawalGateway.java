package com.aewol.external.bank;

import java.math.BigDecimal;

/** 애월지갑 출금액을 회원의 외부 연결 계좌로 보내는 외부 은행 연동 경계. */
public interface BankWithdrawalGateway {
    void withdraw(String bankCode, String accountNumber, BigDecimal amount);
}
