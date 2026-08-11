package com.aewol.domain.member.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.member.dto.MemberResponse;
import com.aewol.domain.member.dto.MemberPasswordChangeRequest;
import com.aewol.domain.member.dto.MemberPasswordVerifyRequest;
import com.aewol.domain.member.dto.MemberUpdateRequest;
import com.aewol.domain.member.dto.SimplePasswordRequest;
import com.aewol.domain.member.service.MemberService;
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
}
