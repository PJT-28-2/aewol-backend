package com.aewol.domain.auth.service;

import com.aewol.domain.auth.dto.LoginRequest;
import com.aewol.domain.auth.dto.KakaoOAuthResponse;
import com.aewol.domain.auth.dto.PasswordResetEmailRequest;
import com.aewol.domain.auth.dto.PasswordResetRequest;
import com.aewol.domain.auth.dto.PasswordResetVerifyRequest;
import com.aewol.domain.auth.dto.PasswordResetVerifyResponse;
import com.aewol.domain.auth.dto.SignupRequest;
import com.aewol.domain.auth.dto.SignupResponse;
import com.aewol.domain.auth.dto.SignupEmailCodeRequest;
import com.aewol.domain.auth.dto.SignupEmailCodeResponse;
import com.aewol.domain.auth.dto.SignupEmailVerificationRequest;
import com.aewol.domain.auth.dto.TokenResponse;

public interface AuthService {
    SignupEmailCodeResponse sendSignupVerificationCode(SignupEmailCodeRequest request);
    void verifySignupEmailCode(SignupEmailVerificationRequest request);
    SignupResponse signup(SignupRequest request);
    TokenResponse login(LoginRequest request);
    KakaoOAuthResponse kakaoLogin(String code);
    TokenResponse refresh(String refreshToken);
    void logout(String memberId);
    SignupEmailCodeResponse sendPasswordResetVerificationCode(PasswordResetEmailRequest request);
    PasswordResetVerifyResponse verifyPasswordResetCode(PasswordResetVerifyRequest request);
    void resetPassword(PasswordResetRequest request);
}
