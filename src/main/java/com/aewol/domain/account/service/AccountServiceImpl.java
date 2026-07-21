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

        String accountId = UUID.randomUUID().toString();
        Map<String, Object> account = new HashMap<>();
        account.put("accountId", accountId);
        account.put("memberId", memberId);
        account.put("bankCode", request.getBankCode());
        account.put("bankName", request.getBankName());
        account.put("accountNumber", request.getAccountNumber());
        account.put("accountHolder", request.getAccountHolder());
        account.put("connectedId", connectedId);
        account.put("isPrimary", request.getIsPrimary() != null && request.getIsPrimary() ? 1 : 0);
        accountMapper.insert(account);

        return AccountResponse.builder()
                .accountId(accountId).bankCode(request.getBankCode()).bankName(request.getBankName())
                .accountNumber(request.getAccountNumber()).accountHolder(request.getAccountHolder())
                .isPrimary(request.getIsPrimary()).status("ACTIVE").build();
    }

    @Override
    public List<AccountResponse> getAccounts(String memberId) {
        return accountMapper.findByMemberId(memberId).stream()
                .map(a -> AccountResponse.builder()
                        .accountId((String) a.get("account_id"))
                        .bankCode((String) a.get("bank_code"))
                        .bankName((String) a.get("bank_name"))
                        .accountNumber((String) a.get("account_number"))
                        .accountHolder((String) a.get("account_holder"))
                        .isPrimary(a.get("is_primary") != null && ((Number) a.get("is_primary")).intValue() == 1)
                        .status((String) a.get("status"))
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void disconnectAccount(String accountId) {
        accountMapper.updateStatus(accountId, "INACTIVE");
    }
}
