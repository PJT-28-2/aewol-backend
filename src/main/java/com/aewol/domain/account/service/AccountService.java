package com.aewol.domain.account.service;

import com.aewol.domain.account.dto.AccountRegisterRequest;
import com.aewol.domain.account.dto.AccountResponse;
import com.aewol.domain.account.dto.DepositConfirmRequest;
import com.aewol.domain.account.dto.DepositConfirmResponse;
import com.aewol.domain.account.dto.DepositVerificationRequest;
import com.aewol.domain.account.dto.DepositVerificationResponse;
import java.util.List;

public interface AccountService {
    DepositVerificationResponse requestDepositVerification(String memberId, DepositVerificationRequest request);
    DepositConfirmResponse confirmDepositVerification(String memberId, DepositConfirmRequest request);
    AccountResponse registerAccount(String memberId, AccountRegisterRequest request);
    List<AccountResponse> getAccounts(String memberId);
    void disconnectAccount(String accountId);
}
