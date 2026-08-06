package com.aewol.domain.account.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.account.dto.AccountRegisterRequest;
import com.aewol.domain.account.dto.AccountResponse;
import com.aewol.domain.account.dto.DepositConfirmRequest;
import com.aewol.domain.account.dto.DepositConfirmResponse;
import com.aewol.domain.account.dto.DepositVerificationRequest;
import com.aewol.domain.account.dto.DepositVerificationResponse;
import com.aewol.domain.account.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name = "Account", description = "연동 계좌 API")
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @Operation(summary = "1원 인증 요청")
    @PostMapping("/verify-deposit")
    public ResponseEntity<ApiResponse<DepositVerificationResponse>> requestDepositVerification(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody DepositVerificationRequest request) {
        return ResponseEntity.ok(ApiResponse.success(accountService.requestDepositVerification(memberId, request)));
    }

    @Operation(summary = "1원 인증 확인")
    @PostMapping("/verify-deposit/confirm")
    public ResponseEntity<ApiResponse<DepositConfirmResponse>> confirmDepositVerification(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody DepositConfirmRequest request) {
        return ResponseEntity.ok(ApiResponse.success(accountService.confirmDepositVerification(memberId, request)));
    }

    @Operation(summary = "계좌 등록 확정")
    @PostMapping
    public ResponseEntity<ApiResponse<AccountResponse>> register(@AuthenticationPrincipal String memberId,
                                                                   @Valid @RequestBody AccountRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(accountService.registerAccount(memberId, request)));
    }

    @Operation(summary = "연동 계좌 목록")
    @GetMapping
    public ResponseEntity<ApiResponse<List<AccountResponse>>> getAccounts(@AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(accountService.getAccounts(memberId)));
    }

    @Operation(summary = "계좌 연동 해제")
    @DeleteMapping("/{accountId}")
    public ResponseEntity<ApiResponse<Void>> disconnect(@PathVariable String accountId) {
        accountService.disconnectAccount(accountId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
