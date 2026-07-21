package com.aewol.domain.donation.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.donation.service.DonationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Tag(name = "Donation", description = "짜투리 저금통/기부 API")
@RestController
@RequestMapping("/api/donation")
@RequiredArgsConstructor
public class DonationController {

    private final DonationService donationService;

    @Operation(summary = "저금통 조회")
    @GetMapping("/pot")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPot(@AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(donationService.getPot(memberId)));
    }

    @Operation(summary = "기부하기")
    @PostMapping("/donate")
    public ResponseEntity<ApiResponse<Void>> donate(@AuthenticationPrincipal String memberId,
                                                     @RequestParam BigDecimal amount,
                                                     @RequestParam String recipientName) {
        donationService.donate(memberId, amount, recipientName);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Operation(summary = "기부 내역")
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getHistory(@AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(donationService.getHistory(memberId)));
    }
}
