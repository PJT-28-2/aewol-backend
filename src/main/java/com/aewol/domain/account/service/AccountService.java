package com.aewol.domain.account.service;

import com.aewol.domain.account.dto.AccountConnectRequest;
import com.aewol.domain.account.dto.AccountResponse;
import java.util.List;

public interface AccountService {
    AccountResponse connectAccount(String memberId, AccountConnectRequest request);
    List<AccountResponse> getAccounts(String memberId);
    void disconnectAccount(String accountId);
}
