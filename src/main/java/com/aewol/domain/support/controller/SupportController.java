package com.aewol.domain.support.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.support.dto.SupportInterestRequest;
import com.aewol.domain.support.dto.SupportProgramsResponse;
import com.aewol.domain.support.service.SupportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Support", description = "반려동물 공공지원정책 API")
@RestController
@RequestMapping("/api/support")
@RequiredArgsConstructor
public class SupportController {

    private final SupportService supportService;

    @Operation(summary = "내 반려동물 맞춤 지원정책")
    @GetMapping({"", "/matched"})
    public ResponseEntity<ApiResponse<SupportProgramsResponse>> matched(
            @AuthenticationPrincipal String memberId,
            @RequestParam(required = false) String petId) {
        return ResponseEntity.ok(ApiResponse.success(supportService.getMatchedPrograms(memberId, petId)));
    }

    @Operation(summary = "지원정책 신청 페이지 이동 상태 저장")
    @PostMapping("/{programId}/interest")
    public ResponseEntity<ApiResponse<Void>> markApplyPageOpened(
            @AuthenticationPrincipal String memberId,
            @PathVariable String programId,
            @Valid @RequestBody SupportInterestRequest request) {
        supportService.markApplyPageOpened(memberId, programId, request.getPetId());
        return ResponseEntity.ok(ApiResponse.success());
    }
}
