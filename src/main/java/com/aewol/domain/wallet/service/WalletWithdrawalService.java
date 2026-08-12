package com.aewol.domain.wallet.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.account.mapper.AccountMapper;
import com.aewol.domain.member.service.SimplePasswordVerificationService;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.dto.WalletWithdrawRequest;
import com.aewol.domain.wallet.dto.WalletWithdrawResponse;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.bank.BankWithdrawalGateway;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WalletWithdrawalService {

    private final AccountMapper accountMapper;
    private final WalletMapper walletMapper;
    private final TransactionMapper transactionMapper;
    private final SimplePasswordVerificationService simplePasswordVerificationService;
    private final BankWithdrawalGateway bankWithdrawalGateway;

    @Transactional
    public WalletWithdrawResponse withdraw(String memberId, WalletWithdrawRequest request) {
        validateAmount(request.getAmount());
        Map<String, Object> account = accountMapper.findByAccountIdForUpdate(request.getAccountId());
        validateWithdrawAccount(memberId, account);

        if (!simplePasswordVerificationService.verify(memberId, request.getPassword())) {
            throw new BusinessException("간편 비밀번호가 일치하지 않습니다.");
        }

        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }

        String walletId = String.valueOf(wallet.get("wallet_id"));
        BigDecimal amount = request.getAmount();
        if (walletMapper.deductBalance(walletId, amount) == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "애월지갑 잔액이 부족합니다.");
        }

        String bankCode = String.valueOf(account.get("bank_code"));
        String accountNumber = String.valueOf(account.get("account_number"));
        LocalDateTime withdrawnAt = LocalDateTime.now();

        // 현재 외부 연동은 DemoBankWithdrawalGateway라 실제 은행 입금은 발생하지 않는다.
        // 정식 이체 사업자 도입 시 멱등키·보상 처리까지 포함한 비동기 출금 흐름으로 교체해야 한다.
        bankWithdrawalGateway.withdraw(bankCode, accountNumber, amount);

        Map<String, Object> transaction = new HashMap<>();
        transaction.put("walletId", walletId);
        transaction.put("txnType", "WITHDRAW");
        transaction.put("price", amount);
        transaction.put("merchantName", account.get("bank_name"));
        transaction.put("memo", normalizeMemo(request.getMemo()));
        transaction.put("autoTagged", "N");
        transaction.put("txnDate", withdrawnAt);
        transactionMapper.insert(transaction);
        if (transaction.get("txnId") == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "출금 거래 기록에 실패했습니다.");
        }

        Map<String, Object> updatedWallet = walletMapper.findByMemberId(memberId);
        if (updatedWallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }

        return WalletWithdrawResponse.builder()
                .transactionId(String.valueOf(transaction.get("txnId")))
                .walletBalance((BigDecimal) updatedWallet.get("balance"))
                .accountId(String.valueOf(account.get("account_id")))
                .bankName(String.valueOf(account.get("bank_name")))
                .accountNumberMasked(maskAccountNumber(accountNumber))
                .withdrawnAt(withdrawnAt)
                .build();
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0 || amount.stripTrailingZeros().scale() > 0) {
            throw new BusinessException("출금 금액은 1원 이상의 원 단위여야 합니다.");
        }
    }

    private void validateWithdrawAccount(String memberId, Map<String, Object> account) {
        if (account == null || !String.valueOf(account.get("member_id")).equals(memberId)) {
            throw BusinessException.notFound("연동된 계좌를 찾을 수 없습니다.");
        }
        if (!"ACTIVE".equals(account.get("status"))) {
            throw BusinessException.conflict("연동 해제된 계좌로 출금할 수 없습니다.");
        }
    }

    private String normalizeMemo(String memo) {
        return memo == null || memo.isBlank() ? "내 계좌로 출금" : memo.trim();
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return "****";
        }
        return "*".repeat(accountNumber.length() - 4) + accountNumber.substring(accountNumber.length() - 4);
    }
}
