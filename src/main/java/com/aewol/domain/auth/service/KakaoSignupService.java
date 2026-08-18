package com.aewol.domain.auth.service;

import com.aewol.domain.auth.dto.KakaoOAuthResponse;
import com.aewol.domain.auth.dto.KakaoPhoneSendCodeRequest;
import com.aewol.domain.auth.dto.KakaoPhoneSendCodeResponse;
import com.aewol.domain.auth.dto.KakaoPhoneVerifyCodeRequest;
import com.aewol.domain.auth.dto.KakaoSignupCompleteRequest;

public interface KakaoSignupService {
    KakaoPhoneSendCodeResponse sendPhoneVerificationCode(KakaoPhoneSendCodeRequest request);
    void verifyPhoneCode(KakaoPhoneVerifyCodeRequest request);
    KakaoOAuthResponse complete(KakaoSignupCompleteRequest request);
}
