package com.aewol.domain.member.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.member.dto.MemberWithdrawRequest;
import com.aewol.domain.member.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User", description = "사용자 API")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final MemberService memberService;

    @Operation(summary = "회원 탈퇴")
    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> withdraw(
            @AuthenticationPrincipal String memberId,
            @RequestBody(required = false) MemberWithdrawRequest request) {
        memberService.withdraw(memberId, request);
        return ResponseEntity.ok(ApiResponse.success("회원탈퇴가 완료되었습니다.", null));
    }
}
