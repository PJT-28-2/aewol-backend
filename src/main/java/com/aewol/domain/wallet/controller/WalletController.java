package com.aewol.domain.wallet.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.wallet.dto.BucketCreateRequest;
import com.aewol.domain.wallet.dto.BucketResponse;
import com.aewol.domain.wallet.dto.WalletResponse;
import com.aewol.domain.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Wallet", description = "지갑/버킷 API")
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

    @Operation(summary = "버킷 목록 조회")
    @GetMapping("/buckets")
    public ResponseEntity<ApiResponse<List<BucketResponse>>> getBuckets(@AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(walletService.getBuckets(memberId)));
    }

    @Operation(summary = "버킷 생성")
    @PostMapping("/buckets")
    public ResponseEntity<ApiResponse<BucketResponse>> createBucket(@AuthenticationPrincipal String memberId,
                                                                     @Valid @RequestBody BucketCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(walletService.createBucket(memberId, request)));
    }

    @Operation(summary = "버킷 수정")
    @PutMapping("/buckets/{bucketId}")
    public ResponseEntity<ApiResponse<Void>> updateBucket(@PathVariable String bucketId,
                                                           @Valid @RequestBody BucketCreateRequest request) {
        walletService.updateBucket(bucketId, request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Operation(summary = "버킷 삭제")
    @DeleteMapping("/buckets/{bucketId}")
    public ResponseEntity<ApiResponse<Void>> deleteBucket(@PathVariable String bucketId) {
        walletService.deleteBucket(bucketId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Operation(summary = "지갑 충전")
    @PostMapping("/deposit")
    public ResponseEntity<ApiResponse<WalletResponse>> deposit(@AuthenticationPrincipal String memberId,
                                                                @RequestParam java.math.BigDecimal amount) {
        return ResponseEntity.ok(ApiResponse.success(walletService.deposit(memberId, amount)));
    }
}
