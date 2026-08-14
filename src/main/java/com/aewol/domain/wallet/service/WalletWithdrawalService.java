package com.aewol.domain.wallet.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.AccountNumberCrypto;
import com.aewol.domain.account.mapper.AccountMapper;
import com.aewol.domain.member.service.SimplePasswordVerificationService;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.dto.WalletWithdrawRequest;
import com.aewol.domain.wallet.dto.WalletWithdrawResponse;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.domain.wallet.mapper.WalletWithdrawalRequestMapper;
import com.aewol.external.bank.BankWithdrawalGateway;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WalletWithdrawalService {

    private final AccountMapper accountMapper;
    private final WalletMapper walletMapper;
    private final TransactionMapper transactionMapper;
    private final WalletWithdrawalRequestMapper withdrawalRequestMapper;
    private final SimplePasswordVerificationService simplePasswordVerificationService;
    private final BankWithdrawalGateway bankWithdrawalGateway;
    private final AccountNumberCrypto accountNumberCrypto;

    @Transactional
    public WalletWithdrawResponse withdraw(String memberId, String idempotencyKey, WalletWithdrawRequest request) {
        validateIdempotencyKey(idempotencyKey);
        validateAmount(request.getAmount());
        String requestHash = createRequestHash(request);

        Map<String, Object> existingRequest = withdrawalRequestMapper
                .findByMemberIdAndKey(memberId, idempotencyKey);
        if (existingRequest != null) {
            return reuseExistingResult(existingRequest, requestHash);
        }

        Map<String, Object> account = accountMapper.findByAccountIdForUpdate(request.getAccountId());
        validateWithdrawAccount(memberId, account);

        Map<String, Object> withdrawalRequest = new HashMap<>();
        withdrawalRequest.put("memberId", memberId);
        withdrawalRequest.put("idempotencyKey", idempotencyKey);
        withdrawalRequest.put("requestHash", requestHash);
        withdrawalRequest.put("accountId", request.getAccountId());
        try {
            withdrawalRequestMapper.insertPending(withdrawalRequest);
        } catch (DuplicateKeyException exception) {
            // 최초 조회와 INSERT 사이에 같은 키의 동시 요청이 완료된 경우다. UNIQUE 제약이
            // 두 요청 중 하나만 PENDING 행을 만들게 하고, 나머지는 최초 결과를 재사용한다.
            Map<String, Object> concurrentRequest = withdrawalRequestMapper
                    .findByMemberIdAndKey(memberId, idempotencyKey);
            if (concurrentRequest == null) {
                throw BusinessException.conflict("동일한 출금 요청이 처리 중입니다.");
            }
            return reuseExistingResult(concurrentRequest, requestHash);
        }
        if (withdrawalRequest.get("withdrawalRequestId") == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "출금 요청 기록에 실패했습니다.");
        }

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
        // account_number는 계좌 연동 시점부터 AES-256-GCM으로 암호화해서 저장한다
        // (AccountServiceImpl 참고). 여기서 복호화하지 않으면 은행 게이트웨이와
        // 마스킹 응답에 암호문이 그대로 나간다 — 실제 출금이 깨진다(PR #162 리뷰 반영).
        String accountNumber = accountNumberCrypto.decrypt(String.valueOf(account.get("account_number")));
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

        WalletWithdrawResponse response = WalletWithdrawResponse.builder()
                .transactionId(String.valueOf(transaction.get("txnId")))
                .walletBalance((BigDecimal) updatedWallet.get("balance"))
                .accountId(String.valueOf(account.get("account_id")))
                .bankName(String.valueOf(account.get("bank_name")))
                .accountNumberMasked(maskAccountNumber(accountNumber))
                .withdrawnAt(withdrawnAt)
                .build();

        Map<String, Object> completedRequest = new HashMap<>();
        completedRequest.put("withdrawalRequestId", withdrawalRequest.get("withdrawalRequestId"));
        completedRequest.put("transactionId", response.getTransactionId());
        completedRequest.put("walletBalance", response.getWalletBalance());
        completedRequest.put("bankName", response.getBankName());
        completedRequest.put("accountNumberMasked", response.getAccountNumberMasked());
        completedRequest.put("withdrawnAt", response.getWithdrawnAt());
        if (withdrawalRequestMapper.complete(completedRequest) != 1) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "출금 요청 완료 기록에 실패했습니다.");
        }

        return response;
    }

    private WalletWithdrawResponse reuseExistingResult(Map<String, Object> existingRequest, String requestHash) {
        if (!requestHash.equals(existingRequest.get("request_hash"))) {
            throw BusinessException.conflict("동일한 멱등키를 다른 출금 요청에 사용할 수 없습니다.");
        }
        if (!"COMPLETED".equals(existingRequest.get("status"))) {
            throw BusinessException.conflict("동일한 출금 요청이 처리 중입니다.");
        }
        return WalletWithdrawResponse.builder()
                .transactionId(String.valueOf(existingRequest.get("transaction_id")))
                .walletBalance((BigDecimal) existingRequest.get("wallet_balance_after"))
                .accountId(String.valueOf(existingRequest.get("account_id")))
                .bankName(String.valueOf(existingRequest.get("bank_name")))
                .accountNumberMasked(String.valueOf(existingRequest.get("account_number_masked")))
                .withdrawnAt(toLocalDateTime(existingRequest.get("withdrawn_at")))
                .build();
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null
                || !idempotencyKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,63}")) {
            throw new BusinessException("Idempotency-Key는 8~64자의 영문, 숫자 또는 ._:- 형식이어야 합니다.");
        }
    }

    private String createRequestHash(WalletWithdrawRequest request) {
        String accountId = request.getAccountId();
        String amount = request.getAmount().stripTrailingZeros().toPlainString();
        String memo = normalizeMemo(request.getMemo());
        String canonicalRequest = accountId.length() + ":" + accountId
                + "|" + amount + "|" + memo.length() + ":" + memo;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 미지원 환경", exception);
        }
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }
        throw new IllegalStateException("출금 완료 시각 형식이 올바르지 않습니다.");
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
