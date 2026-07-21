package com.aewol.domain.transaction.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.transaction.dto.PaymentRequest;
import com.aewol.domain.transaction.dto.TransactionResponse;
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

    @Operation(summary = "결제 (자동 태깅)")
    @PostMapping("/payment")
    public ResponseEntity<ApiResponse<TransactionResponse>> payment(@AuthenticationPrincipal String memberId,
                                                                     @Valid @RequestBody PaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(transactionService.processPayment(memberId, request)));
    }

    @Operation(summary = "거래 내역 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<List<TransactionResponse>>> getTransactions(
            @AuthenticationPrincipal String memberId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String petId) {
        return ResponseEntity.ok(ApiResponse.success(transactionService.getTransactions(memberId, category, petId)));
    }

    @Operation(summary = "거래 상세 조회")
    @GetMapping("/{txnId}")
    public ResponseEntity<ApiResponse<TransactionResponse>> getTransaction(@PathVariable String txnId) {
        return ResponseEntity.ok(ApiResponse.success(transactionService.getTransaction(txnId)));
    }
}
