package com.aewol.domain.account.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.account.dto.AccountRegisterRequest;
import com.aewol.domain.account.dto.AccountResponse;
import com.aewol.domain.account.dto.DepositConfirmRequest;
import com.aewol.domain.account.dto.DepositConfirmResponse;
import com.aewol.domain.account.dto.DepositVerificationRequest;
import com.aewol.domain.account.dto.DepositVerificationResponse;
import com.aewol.domain.account.mapper.AccountMapper;
import com.aewol.domain.account.mapper.AccountVerificationMapper;
import com.aewol.external.codef.CodefClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountMapper accountMapper;
    private final AccountVerificationMapper accountVerificationMapper;
    private final CodefClient codefClient;

    // 프론트(store.requestDepositAuth의 DEPOSIT_AUTH_TIMEOUT_SECONDS)와 동일한 유효시간.
    // 여기서 안 맞추면 프론트는 아직 타이머가 남았다고 보여주는데 서버는 이미
    // 만료 처리하는 상황이 생길 수 있다.
    private static final long DEPOSIT_AUTH_TIMEOUT_SECONDS = 180;

    @Override
    @Transactional
    public DepositVerificationResponse requestDepositVerification(String memberId, DepositVerificationRequest request) {
        String transactionId = generateTransactionId();

        // CODEF 1원 이체 요청 — CODEF가 랜덤 입금자명(authCode)을 직접 생성해서 돌려준다.
        // 실패하면 예외로 여기서 끊기고 account_verification에 아무것도 안 남는다.
        String depositorName = codefClient.requestAccountTransferAuth(request.getBankCode(), request.getAccountNumber());

        Map<String, Object> verification = new HashMap<>();
        verification.put("transactionId", transactionId);
        verification.put("memberId", memberId);
        verification.put("bankCode", request.getBankCode());
        verification.put("accountNumber", request.getAccountNumber());
        verification.put("verificationCode", depositorName);
        // DB DEFAULT CURRENT_TIMESTAMP에 맡기면 MySQL 컨테이너 시간대(TZ 미설정 시 UTC)와
        // JVM 로컬 시간대(KST)가 어긋나서 isExpired()가 저장 직후에도 만료로 오판할 수 있다.
        // 항상 JVM 기준 시각을 직접 저장해서 isExpired()의 LocalDateTime.now()와 같은 기준을 쓰게 한다.
        verification.put("requestedAt", LocalDateTime.now());
        accountVerificationMapper.insert(verification);

        return DepositVerificationResponse.builder()
                .transactionId(transactionId)
                .depositorNameLength(depositorName.length())
                .depositorNameForTest(depositorName)
                .build();
    }

    @Override
    @Transactional
    public DepositConfirmResponse confirmDepositVerification(String memberId, DepositConfirmRequest request) {
        Map<String, Object> verification = accountVerificationMapper.findById(request.getTransactionId());
        if (verification == null || !String.valueOf(verification.get("member_id")).equals(memberId)) {
            throw BusinessException.notFound("1원 인증 요청을 찾을 수 없어요");
        }
        if (!"PENDING".equals(verification.get("status"))) {
            // 이미 VERIFIED/USED인 건을 다시 확인하려는 경우 — 재사용 방지를 위해 실패로 처리
            return DepositConfirmResponse.builder().verified(false).reason("ALREADY_USED").build();
        }
        if (isExpired(verification.get("requested_at"))) {
            return DepositConfirmResponse.builder().verified(false).reason("EXPIRED").build();
        }

        boolean matched = String.valueOf(verification.get("verification_code")).equals(request.getVerificationCode());
        if (matched) {
            accountVerificationMapper.updateStatus(request.getTransactionId(), "VERIFIED");
        }
        return DepositConfirmResponse.builder()
                .verified(matched)
                .reason(matched ? null : "MISMATCH")
                .build();
    }

    @Override
    @Transactional
    public AccountResponse registerAccount(String memberId, AccountRegisterRequest request) {
        Map<String, Object> verification = accountVerificationMapper.findById(request.getTransactionId());
        if (verification == null || !String.valueOf(verification.get("member_id")).equals(memberId)) {
            throw BusinessException.notFound("1원 인증 요청을 찾을 수 없어요");
        }
        if (!"VERIFIED".equals(verification.get("status"))) {
            throw new BusinessException(HttpStatus.CONFLICT, "1원 인증이 완료되지 않았어요");
        }

        String bankCode = (String) verification.get("bank_code");
        String accountNumber = (String) verification.get("account_number");

        Map<String, Object> account = new HashMap<>();
        account.put("memberId", memberId);
        account.put("bankCode", bankCode);
        account.put("accountNumber", accountNumber);
        account.put("accountNumberHash", sha256(accountNumber));
        account.put("isPrimary", request.getIsPrimary() != null && request.getIsPrimary() ? 1 : 0);
        accountMapper.insert(account); // account_id AUTO_INCREMENT로 채워짐

        // transaction_id 재사용/이중 등록 방지
        accountVerificationMapper.updateStatus(request.getTransactionId(), "USED");

        Map<String, Object> saved = accountMapper.findByAccountId(String.valueOf(account.get("accountId")));
        return toAccountResponse(saved);
    }

    @Override
    public List<AccountResponse> getAccounts(String memberId) {
        return accountMapper.findByMemberId(memberId).stream()
                .map(this::toAccountResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void disconnectAccount(String accountId) {
        accountMapper.updateStatus(accountId, "INACTIVE");
    }

    // 연동된 외부 은행 계좌의 실제 잔액은 조회하지 않는다(2026-08-06 결정) — 실시간 조회는
    // CODEF에 사용자의 인터넷뱅킹 아이디/비밀번호를 넘겨야 해서 신뢰 장벽이 크고, 애월의
    // 핵심 기능(버킷/지출관리)은 어차피 이 계좌 잔액이 아니라 내부 지갑(wallet) 잔액만
    // 쓴다. AccountResponse에도 balance 필드 자체가 없다.
    private AccountResponse toAccountResponse(Map<String, Object> a) {
        return AccountResponse.builder()
                .accountId(String.valueOf(a.get("account_id")))
                .bankCode((String) a.get("bank_code"))
                .bankName((String) a.get("bank_name"))
                .accountNumber((String) a.get("account_number"))
                .isPrimary(toBool(a.get("is_primary")))
                .status((String) a.get("status"))
                .build();
    }

    /** TX + yyyyMMdd + 6자리 랜덤(V4 코멘트의 "TX20260722001" 형식과 동일 계열, 순번 대신 랜덤으로 충돌 방지) */
    private String generateTransactionId() {
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int random = ThreadLocalRandom.current().nextInt(1_000_000);
        return "TX" + datePart + String.format("%06d", random);
    }

    // MyBatis 드라이버 설정에 따라 DATETIME 컬럼이 Timestamp/LocalDateTime/Date
    // 중 무엇으로 매핑되는지 달라질 수 있어서 셋 다 방어적으로 처리한다.
    private boolean isExpired(Object requestedAt) {
        LocalDateTime requestedTime;
        if (requestedAt instanceof Timestamp) {
            requestedTime = ((Timestamp) requestedAt).toLocalDateTime();
        } else if (requestedAt instanceof LocalDateTime) {
            requestedTime = (LocalDateTime) requestedAt;
        } else if (requestedAt instanceof java.util.Date) {
            requestedTime = LocalDateTime.ofInstant(((java.util.Date) requestedAt).toInstant(), java.time.ZoneId.systemDefault());
        } else {
            return false;
        }
        return requestedTime.plusSeconds(DEPOSIT_AUTH_TIMEOUT_SECONDS).isBefore(LocalDateTime.now());
    }

    /** 계좌번호 중복 등록 방지용 해시 (V4 uk_linked_member_account_hash) */
    private String sha256(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 환경", e);
        }
    }

    /** TINYINT(1)은 커넥터 설정에 따라 Boolean 또는 Number로 반환된다 */
    private static boolean toBool(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() == 1;
        return false;
    }
}
