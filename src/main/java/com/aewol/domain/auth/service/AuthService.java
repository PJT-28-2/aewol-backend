package com.aewol.domain.auth.service;

import com.aewol.domain.auth.dto.LoginRequest;
import com.aewol.domain.auth.dto.SignupRequest;
import com.aewol.domain.auth.dto.TokenResponse;
import com.aewol.domain.auth.dto.VerifyRequest;

public interface AuthService {
    void signup(SignupRequest request);
    TokenResponse verifyEmail(VerifyRequest request);
    TokenResponse login(LoginRequest request);
    TokenResponse kakaoLogin(String code);
    TokenResponse refresh(String refreshToken);
    void logout(String memberId);
    void withdraw(String memberId);
}
