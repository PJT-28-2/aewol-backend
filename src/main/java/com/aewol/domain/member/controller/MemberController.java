package com.aewol.domain.member.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.member.dto.MemberResponse;
import com.aewol.domain.member.dto.MemberPasswordChangeRequest;
import com.aewol.domain.member.dto.MemberPasswordVerifyRequest;
import com.aewol.domain.member.dto.MemberPhoneSendCodeRequest;
import com.aewol.domain.member.dto.MemberPhoneSendCodeResponse;
import com.aewol.domain.member.dto.MemberPhoneVerifyCodeRequest;
import com.aewol.domain.member.dto.MemberUpdateRequest;
import com.aewol.domain.member.dto.SimplePasswordRequest;
import com.aewol.domain.member.dto.SimplePasswordVerifyRequest;
import com.aewol.domain.member.dto.SimplePasswordVerifyResponse;
import com.aewol.domain.member.service.MemberService;
import com.aewol.domain.member.service.SimplePasswordVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Member", description = "회원 API")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;
    private final SimplePasswordVerificationService simplePasswordVerificationService;

    @Operation(summary = "내 정보 조회")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MemberResponse>> getMyInfo(@AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(memberService.getMember(memberId)));
    }

    @Operation(summary = "내 정보 수정")
    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<Void>> updateMyInfo(@AuthenticationPrincipal String memberId,
                                                          @Valid @RequestBody MemberUpdateRequest request) {
        memberService.updateMember(memberId, request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Operation(summary = "전화번호 변경 인증번호 발송")
    @PostMapping("/me/phone/send-code")
    public ResponseEntity<ApiResponse<MemberPhoneSendCodeResponse>> sendPhoneVerificationCode(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody MemberPhoneSendCodeRequest request) {
        MemberPhoneSendCodeResponse result =
                memberService.sendPhoneVerificationCode(memberId, request);
        return ResponseEntity.ok(ApiResponse.success("인증번호가 발송되었습니다.", result));
    }

    @Operation(summary = "전화번호 변경 인증번호 검증")
    @PostMapping("/me/phone/verify-code")
    public ResponseEntity<ApiResponse<Void>> verifyPhoneCode(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody MemberPhoneVerifyCodeRequest request) {
        memberService.verifyPhoneCode(memberId, request);
        return ResponseEntity.ok(ApiResponse.success("전화번호 인증이 완료되었습니다.", null));
    }

    @Operation(summary = "현재 비밀번호 확인")
    @PostMapping("/me/password/verify")
    public ResponseEntity<ApiResponse<Void>> verifyPassword(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody MemberPasswordVerifyRequest request) {
        memberService.verifyPassword(memberId, request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Operation(summary = "비밀번호 변경")
    @PatchMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody MemberPasswordChangeRequest request) {
        memberService.changePassword(memberId, request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Operation(summary = "간편 비밀번호 설정")
    @PostMapping("/simple-password")
    public ResponseEntity<ApiResponse<Void>> setSimplePassword(@AuthenticationPrincipal String memberId,
                                                                 @Valid @RequestBody SimplePasswordRequest request) {
        memberService.setSimplePassword(memberId, request);
        return ResponseEntity.ok(ApiResponse.success("간편 비밀번호가 설정되었습니다.", null));
    }

    @Operation(summary = "간편 비밀번호 검증")
    @PostMapping("/simple-password/verify")
    public ResponseEntity<ApiResponse<SimplePasswordVerifyResponse>> verifySimplePassword(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody SimplePasswordVerifyRequest request) {
        boolean verified = simplePasswordVerificationService.verify(memberId, request.getPassword());
        return ResponseEntity.ok(ApiResponse.success(new SimplePasswordVerifyResponse(verified)));
    }
}
