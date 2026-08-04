package com.aewol.domain.wallet.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.wallet.dto.WalletResponse;
import com.aewol.domain.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Wallet", description = "지갑 API")
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @Operation(summary = "내 지갑 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<WalletResponse>> getMyWallet(@AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(walletService.getWallet(memberId)));
    }

    @Operation(summary = "지갑 충전")
    @PostMapping("/deposit")
    public ResponseEntity<ApiResponse<WalletResponse>> deposit(@AuthenticationPrincipal String memberId,
                                                                @RequestParam java.math.BigDecimal amount) {
        return ResponseEntity.ok(ApiResponse.success(walletService.deposit(memberId, amount)));
    }
}
