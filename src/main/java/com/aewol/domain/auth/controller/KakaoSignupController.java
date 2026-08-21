package com.aewol.domain.auth.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.auth.dto.KakaoOAuthResponse;
import com.aewol.domain.auth.dto.KakaoPhoneSendCodeRequest;
import com.aewol.domain.auth.dto.KakaoPhoneSendCodeResponse;
import com.aewol.domain.auth.dto.KakaoPhoneVerifyCodeRequest;
import com.aewol.domain.auth.dto.KakaoSignupCompleteRequest;
import com.aewol.domain.auth.service.KakaoSignupService;
import com.aewol.domain.auth.support.KakaoRegistrationCookie;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "인증/인가 API")
@RestController
@RequestMapping("/api/auth/oauth/kakao/signup")
@RequiredArgsConstructor
public class KakaoSignupController {

    private final KakaoSignupService kakaoSignupService;
    private final KakaoRegistrationCookie kakaoRegistrationCookie;

    @Operation(summary = "카카오 신규회원 전화번호 인증번호 발송")
    @PostMapping("/phone/send-code")
    public ResponseEntity<ApiResponse<KakaoPhoneSendCodeResponse>> sendPhoneCode(
            @Valid @RequestBody KakaoPhoneSendCodeRequest request,
            HttpServletRequest httpRequest) {
        kakaoRegistrationCookie.requireMatches(httpRequest, request.getRegistrationToken());
        KakaoPhoneSendCodeResponse result =
                kakaoSignupService.sendPhoneVerificationCode(request);
        return ResponseEntity.ok(ApiResponse.success("인증번호가 발송되었습니다.", result));
    }

    @Operation(summary = "카카오 신규회원 전화번호 인증번호 검증")
    @PostMapping("/phone/verify-code")
    public ResponseEntity<ApiResponse<Void>> verifyPhoneCode(
            @Valid @RequestBody KakaoPhoneVerifyCodeRequest request,
            HttpServletRequest httpRequest) {
        kakaoRegistrationCookie.requireMatches(httpRequest, request.getRegistrationToken());
        kakaoSignupService.verifyPhoneCode(request);
        return ResponseEntity.ok(ApiResponse.success("전화번호 인증이 완료되었습니다.", null));
    }

    @Operation(summary = "카카오 신규회원 가입 완료")
    @PostMapping("/complete")
    public ResponseEntity<ApiResponse<KakaoOAuthResponse>> complete(
            @Valid @RequestBody KakaoSignupCompleteRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        kakaoRegistrationCookie.requireMatches(httpRequest, request.getRegistrationToken());
        KakaoOAuthResponse result = kakaoSignupService.complete(request);
        kakaoRegistrationCookie.clear(httpResponse);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("카카오 회원가입이 완료되었습니다.", result));
    }
}
