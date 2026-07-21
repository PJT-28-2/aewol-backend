package com.aewol.domain.share.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.share.service.ShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@Tag(name = "Share", description = "공동 양육 API")
@RestController
@RequestMapping("/api/share")
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;

    @Operation(summary = "공유 멤버 초대")
    @PostMapping("/invite")
    public ResponseEntity<ApiResponse<Void>> invite(@AuthenticationPrincipal String memberId,
                                                     @RequestBody Map<String, Object> request) {
        shareService.invite(memberId, request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Operation(summary = "초대 수락/거절")
    @PutMapping("/{accessId}")
    public ResponseEntity<ApiResponse<Void>> respond(@PathVariable String accessId,
                                                      @RequestParam String status) {
        shareService.respondInvite(accessId, status);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Operation(summary = "공유 멤버 목록")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSharedMembers(@AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(shareService.getSharedMembers(memberId)));
    }
}
