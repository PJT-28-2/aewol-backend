package com.aewol.domain.share.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.share.dto.*;
import com.aewol.domain.share.service.ShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Share", description = "공동 양육 API")
@RestController
@RequestMapping("/api/share")
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;

    @Operation(summary = "공동육아 가능한 반려동물 목록")
    @GetMapping("/pets")
    public ResponseEntity<ApiResponse<List<SharePetResponse>>> getPets(
            @AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(shareService.getAccessiblePets(memberId)));
    }

    @Operation(summary = "이메일 또는 휴대전화 공동육아 초대")
    @PostMapping("/invite")
    public ResponseEntity<ApiResponse<ShareInviteResponse>> invite(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody ShareInviteRequest request) {
        return ResponseEntity.ok(ApiResponse.success(shareService.invite(memberId, request)));
    }

    @Operation(summary = "링크 공동육아 초대")
    @PostMapping("/invite/link")
    public ResponseEntity<ApiResponse<ShareInviteResponse>> createLinkInvite(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody ShareLinkInviteRequest request) {
        return ResponseEntity.ok(ApiResponse.success(shareService.createLinkInvite(memberId, request)));
    }

    @Operation(summary = "초대 코드 확인")
    @GetMapping("/invites/{inviteCode}")
    public ResponseEntity<ApiResponse<ShareInviteDetailResponse>> getInvite(
            @PathVariable String inviteCode) {
        return ResponseEntity.ok(ApiResponse.success(shareService.getInvite(inviteCode)));
    }

    @Operation(summary = "초대 코드 수락")
    @PostMapping("/invites/{inviteCode}/accept")
    public ResponseEntity<ApiResponse<Void>> acceptInvite(
            @AuthenticationPrincipal String memberId,
            @PathVariable String inviteCode) {
        shareService.acceptInvite(memberId, inviteCode);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Operation(summary = "초대 수락 또는 거절")
    @PutMapping("/{accessId}")
    public ResponseEntity<ApiResponse<Void>> respond(
            @AuthenticationPrincipal String memberId,
            @PathVariable String accessId,
            @RequestParam String status) {
        shareService.respondInvite(memberId, accessId, status);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Operation(summary = "반려동물 공동육아 구성원")
    @GetMapping("/{petId}/members")
    public ResponseEntity<ApiResponse<List<ShareMemberResponse>>> getMembers(
            @AuthenticationPrincipal String memberId,
            @PathVariable String petId) {
        return ResponseEntity.ok(ApiResponse.success(shareService.getMembers(memberId, petId)));
    }

    @Operation(summary = "이번 달 공동육아 기여도")
    @GetMapping("/contributions")
    public ResponseEntity<ApiResponse<List<ShareContributionResponse>>> getContributions(
            @AuthenticationPrincipal String memberId,
            @RequestParam String petId) {
        return ResponseEntity.ok(ApiResponse.success(shareService.getContributions(memberId, petId)));
    }

    @Operation(summary = "반려동물 공동육아 활동 내역")
    @GetMapping("/logs")
    public ResponseEntity<ApiResponse<List<ShareActivityResponse>>> getLogs(
            @AuthenticationPrincipal String memberId,
            @RequestParam String petId) {
        return ResponseEntity.ok(ApiResponse.success(shareService.getLogs(memberId, petId)));
    }

    @Operation(summary = "공동육아 구성원 권한 변경")
    @PatchMapping("/members/{memberId}/role")
    public ResponseEntity<ApiResponse<Void>> updateRole(
            @AuthenticationPrincipal String ownerId,
            @PathVariable String memberId,
            @Valid @RequestBody ShareRoleUpdateRequest request) {
        shareService.updateRole(ownerId, memberId, request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Operation(summary = "공동육아 구성원 내보내기")
    @DeleteMapping("/members/{memberId}")
    public ResponseEntity<ApiResponse<Void>> removeMember(
            @AuthenticationPrincipal String ownerId,
            @PathVariable String memberId,
            @RequestParam String petId) {
        shareService.removeMember(ownerId, petId, memberId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
