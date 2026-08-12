package com.aewol.external.bank;

import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 외부 은행 이체 사업자 확정 전까지 사용하는 데모 구현.
 * 실제 계좌로 돈을 보내지 않으며, 지갑 잔액과 거래 원장만 애월 DB에 반영된다.
 */
@Slf4j
@Component
public class DemoBankWithdrawalGateway implements BankWithdrawalGateway {

    @Override
    public void withdraw(String bankCode, String accountNumber, BigDecimal amount) {
        log.info("[DEMO] 외부 계좌 출금 처리 - bankCode: {}, account: {}, amount: {}",
                bankCode, maskAccountNumber(accountNumber), amount);
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return "****";
        }
        return "*".repeat(accountNumber.length() - 4) + accountNumber.substring(accountNumber.length() - 4);
    }
}
