package com.aewol.domain.account.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.account.dto.AccountConnectRequest;
import com.aewol.domain.account.dto.AccountResponse;
import com.aewol.domain.account.mapper.AccountMapper;
import com.aewol.external.codef.CodefClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountMapper accountMapper;
    private final CodefClient codefClient;

    @Override
    @Transactional
    public AccountResponse connectAccount(String memberId, AccountConnectRequest request) {
        String connectedId = codefClient.createConnectedId(request.getBankCode(), request.getAccountNumber());

        Map<String, Object> account = new HashMap<>();
        account.put("memberId", memberId);
        account.put("bankCode", request.getBankCode());
        account.put("accountNumber", request.getAccountNumber());
        account.put("accountNumberHash", sha256(request.getAccountNumber()));
        account.put("accountHolder", request.getAccountHolder());
        account.put("connectedId", connectedId);
        account.put("isPrimary", request.getIsPrimary() != null && request.getIsPrimary() ? 1 : 0);
        accountMapper.insert(account); // account_id AUTO_INCREMENT

        return AccountResponse.builder()
                .accountId(String.valueOf(account.get("accountId")))
                .bankCode(request.getBankCode()).bankName(request.getBankName())
                .accountNumber(request.getAccountNumber()).accountHolder(request.getAccountHolder())
                .isPrimary(request.getIsPrimary()).status("ACTIVE").build();
    }

    @Override
    public List<AccountResponse> getAccounts(String memberId) {
        return accountMapper.findByMemberId(memberId).stream()
                .map(a -> AccountResponse.builder()
                        .accountId(String.valueOf(a.get("account_id")))
                        .bankCode((String) a.get("bank_code"))
                        .bankName((String) a.get("bank_name"))
                        .accountNumber((String) a.get("account_number"))
                        .accountHolder((String) a.get("account_holder"))
                        .isPrimary(toBool(a.get("is_primary")))
                        .status((String) a.get("status"))
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void disconnectAccount(String accountId) {
        accountMapper.updateStatus(accountId, "INACTIVE");
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
