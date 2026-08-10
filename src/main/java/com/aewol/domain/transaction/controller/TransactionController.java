package com.aewol.domain.transaction.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.transaction.dto.PaymentRequest;
import com.aewol.domain.transaction.dto.TossPaymentConfirmRequest;
import com.aewol.domain.transaction.dto.TransactionResponse;
import com.aewol.domain.transaction.dto.TransactionTagUpdateRequest;
import com.aewol.domain.transaction.dto.TransactionPageResponse;
import com.aewol.domain.transaction.service.TossPaymentService;
import com.aewol.domain.transaction.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Transaction", description = "거래/결제 API")
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;
    private final TossPaymentService tossPaymentService;

    @Operation(summary = "결제 (자동 태깅)")
    @PostMapping("/payment")
    public ResponseEntity<ApiResponse<TransactionResponse>> payment(@AuthenticationPrincipal String memberId,
                                                                     @Valid @RequestBody PaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(transactionService.processPayment(memberId, request)));
    }

    @Operation(summary = "Toss 결제 승인 확정")
    @PostMapping("/toss-confirm")
    public ResponseEntity<ApiResponse<TransactionResponse>> tossConfirm(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody TossPaymentConfirmRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(tossPaymentService.confirm(memberId, request)));
    }

    @Operation(summary = "거래 내역 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<TransactionPageResponse>> getTransactions(
            @AuthenticationPrincipal String memberId,
            @RequestParam(defaultValue = "ALL") String type,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                transactionService.getTransactions(memberId, type, period, cursor, size)));
    }

    @Operation(summary = "최근 거래 내역 조회")
    @GetMapping("/recent")
    public ResponseEntity<ApiResponse<List<TransactionResponse>>> getRecentTransactions(
            @AuthenticationPrincipal String memberId,
            @RequestParam(defaultValue = "ALL") String type,
            @RequestParam(defaultValue = "4") int limit) {
        return ResponseEntity.ok(ApiResponse.success(
                transactionService.getRecentTransactions(memberId, type, limit)));
    }

    @Operation(summary = "거래 상세 조회")
    @GetMapping("/{txnId}")
    public ResponseEntity<ApiResponse<TransactionResponse>> getTransaction(
            @AuthenticationPrincipal String memberId, @PathVariable String txnId) {
        return ResponseEntity.ok(ApiResponse.success(transactionService.getTransaction(memberId, txnId)));
    }

    @Operation(summary = "거래 카테고리·반려동물 태그 수정")
    @PutMapping("/{txnId}/tag")
    public ResponseEntity<ApiResponse<TransactionResponse>> updateTag(
            @AuthenticationPrincipal String memberId,
            @PathVariable String txnId,
            @Valid @RequestBody TransactionTagUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(transactionService.updateTag(memberId, txnId, request)));
    }
}
