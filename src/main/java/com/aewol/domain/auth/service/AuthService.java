package com.aewol.domain.auth.service;

import com.aewol.domain.auth.dto.LoginRequest;
import com.aewol.domain.auth.dto.SignupRequest;
import com.aewol.domain.auth.dto.SignupResponse;
import com.aewol.domain.auth.dto.SignupEmailCodeRequest;
import com.aewol.domain.auth.dto.SignupEmailCodeResponse;
import com.aewol.domain.auth.dto.SignupEmailVerificationRequest;
import com.aewol.domain.auth.dto.TokenResponse;
import com.aewol.domain.auth.dto.VerifyRequest;

public interface AuthService {
    SignupEmailCodeResponse sendSignupVerificationCode(SignupEmailCodeRequest request);
    void verifySignupEmailCode(SignupEmailVerificationRequest request);
    SignupResponse signup(SignupRequest request);
    TokenResponse verifyEmail(VerifyRequest request);
    TokenResponse login(LoginRequest request);
    TokenResponse kakaoLogin(String code);
    TokenResponse refresh(String refreshToken);
    void logout(String memberId);
}
