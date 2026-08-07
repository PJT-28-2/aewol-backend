package com.aewol.domain.donation.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.donation.dto.*;
import com.aewol.domain.donation.service.DonationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Donation", description = "짜투리 저금통과 기부 API")
@RestController
@RequestMapping("/api/donation")
@RequiredArgsConstructor
public class DonationController {

    private final DonationService donationService;

    @Operation(summary = "저금통·캠페인·설정 통합 조회")
    @GetMapping({"", "/pot"})
    public ResponseEntity<ApiResponse<DonationOverviewResponse>> getOverview(
            @AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(donationService.getOverview(memberId)));
    }

    @Operation(summary = "저금통에서 기부")
    @PostMapping({"", "/donate"})
    public ResponseEntity<ApiResponse<DonationBalanceResponse>> donate(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody DonationRequest request) {
        return ResponseEntity.ok(ApiResponse.success(donationService.donate(memberId, request)));
    }

    @Operation(summary = "저금통 잔액을 내 지갑으로 출금")
    @PostMapping("/pot/withdraw")
    public ResponseEntity<ApiResponse<DonationBalanceResponse>> withdraw(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody DonationWithdrawRequest request) {
        return ResponseEntity.ok(ApiResponse.success(donationService.withdraw(memberId, request)));
    }

    @Operation(summary = "저금통 설정 저장")
    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<DonationSettingsResponse>> saveSettings(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody DonationSettingRequest request) {
        return ResponseEntity.ok(ApiResponse.success(donationService.saveSettings(memberId, request)));
    }

    @Operation(summary = "기부 내역")
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<DonationHistoryResponse>>> getHistory(
            @AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(donationService.getHistory(memberId)));
    }
}
