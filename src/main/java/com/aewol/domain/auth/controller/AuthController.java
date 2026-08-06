package com.aewol.domain.auth.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.auth.dto.LoginRequest;
import com.aewol.domain.auth.dto.SignupRequest;
import com.aewol.domain.auth.dto.SignupResponse;
import com.aewol.domain.auth.dto.SignupEmailCodeRequest;
import com.aewol.domain.auth.dto.SignupEmailCodeResponse;
import com.aewol.domain.auth.dto.SignupEmailVerificationRequest;
import com.aewol.domain.auth.dto.TokenResponse;
import com.aewol.domain.auth.dto.VerifyRequest;
import com.aewol.domain.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "인증/인가 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "회원가입 이메일 인증번호 발송")
    @PostMapping("/signup/send-code")
    public ResponseEntity<ApiResponse<SignupEmailCodeResponse>> sendSignupVerificationCode(
            @Valid @RequestBody SignupEmailCodeRequest request) {
        SignupEmailCodeResponse result = authService.sendSignupVerificationCode(request);
        return ResponseEntity.ok(ApiResponse.success("회원가입 인증번호가 발송되었습니다.", result));
    }

    @Operation(summary = "회원가입 이메일 인증번호 검증")
    @PostMapping("/signup/verify-code")
    public ResponseEntity<ApiResponse<Void>> verifySignupEmailCode(
            @Valid @RequestBody SignupEmailVerificationRequest request) {
        authService.verifySignupEmailCode(request);
        return ResponseEntity.ok(ApiResponse.success("이메일 인증이 완료되었습니다.", null));
    }

    @Operation(summary = "회원가입")
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(@Valid @RequestBody SignupRequest request) {
        SignupResponse result = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("회원가입이 완료되었습니다.", result));
    }

    @Operation(summary = "이메일 인증 확인")
    @PostMapping("/signup/verify")
    public ResponseEntity<ApiResponse<TokenResponse>> verify(@Valid @RequestBody VerifyRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.verifyEmail(request)));
    }

    @Operation(summary = "로그인")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.login(request)));
    }

    @Operation(summary = "카카오 소셜 로그인")
    @PostMapping("/oauth/kakao")
    public ResponseEntity<ApiResponse<TokenResponse>> kakaoLogin(@RequestParam String code) {
        return ResponseEntity.ok(ApiResponse.success(authService.kakaoLogin(code)));
    }

    @Operation(summary = "토큰 재발급")
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(@RequestHeader("X-Refresh-Token") String refreshToken) {
        return ResponseEntity.ok(ApiResponse.success(authService.refresh(refreshToken)));
    }

    @Operation(summary = "로그아웃")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@AuthenticationPrincipal String memberId) {
        authService.logout(memberId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Operation(summary = "회원 탈퇴")
    @DeleteMapping("/withdraw")
    public ResponseEntity<ApiResponse<Void>> withdraw(@AuthenticationPrincipal String memberId) {
        authService.withdraw(memberId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
