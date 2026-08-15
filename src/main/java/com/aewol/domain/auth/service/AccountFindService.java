package com.aewol.domain.auth.service;

import com.aewol.domain.auth.dto.AccountFindResultResponse;
import com.aewol.domain.auth.dto.AccountFindSendCodeRequest;
import com.aewol.domain.auth.dto.AccountFindSendCodeResponse;
import com.aewol.domain.auth.dto.AccountFindVerifyRequest;

public interface AccountFindService {
    AccountFindSendCodeResponse sendVerificationCode(AccountFindSendCodeRequest request);
    AccountFindResultResponse verifyCode(AccountFindVerifyRequest request);
}
