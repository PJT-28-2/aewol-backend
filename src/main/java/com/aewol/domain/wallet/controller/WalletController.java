package com.aewol.domain.wallet.controller;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.response.ApiResponse;
import com.aewol.domain.wallet.dto.TossChargeOrderRequest;
import com.aewol.domain.wallet.dto.TossChargeOrderResponse;
import com.aewol.domain.wallet.dto.TossChargeRequest;
import com.aewol.domain.wallet.dto.WalletResponse;
import com.aewol.domain.wallet.dto.WalletWithdrawRequest;
import com.aewol.domain.wallet.dto.WalletWithdrawResponse;
import com.aewol.domain.wallet.service.TossChargeService;
import com.aewol.domain.wallet.service.WalletService;
import com.aewol.domain.wallet.service.WalletWithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Wallet", description = "지갑 API")
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final TossChargeService tossChargeService;
    private final WalletWithdrawalService walletWithdrawalService;

    // Toss 승인 없이 MAIN 지갑을 올리는 데모용 API. prod 기본값은 false다.
    @Value("${wallet.allow-unverified-deposit:false}")
    private boolean allowUnverifiedDeposit;

    @Operation(summary = "내 지갑 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<WalletResponse>> getMyWallet(@AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(walletService.getWallet(memberId)));
    }

    @Operation(summary = "지갑 충전(무결제, 로컬·개발 전용)",
            description = "Toss 승인 없이 MAIN 지갑 잔액을 올린다. "
                    + "wallet.allow-unverified-deposit=true 인 환경에서만 동작하며, "
                    + "운영 충전은 /api/wallet/toss-charge를 쓴다.")
    @PostMapping("/deposit")
    public ResponseEntity<ApiResponse<WalletResponse>> deposit(@AuthenticationPrincipal String memberId,
                                                                @RequestParam java.math.BigDecimal amount) {
        if (!allowUnverifiedDeposit) {
            throw BusinessException.forbidden("결제 확인 없는 충전은 할 수 없습니다. Toss 충전을 이용해 주세요.");
        }
        return ResponseEntity.ok(ApiResponse.success(walletService.deposit(memberId, amount)));
    }

    @Operation(summary = "애월지갑 출금",
            description = "MAIN 애월지갑 잔액을 본인 명의의 활성 연결 계좌로 출금한다. "
                    + "현재 외부 은행 입금은 데모 처리이며 지갑 차감과 거래 원장은 실제 반영한다.")
    @PostMapping("/withdraw")
    public ResponseEntity<ApiResponse<WalletWithdrawResponse>> withdraw(
            @AuthenticationPrincipal String memberId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WalletWithdrawRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                walletWithdrawalService.withdraw(memberId, idempotencyKey, request)));
    }

    @Operation(summary = "Toss 충전 주문 생성",
            description = "Toss 결제창을 열기 전에 서버에서 orderId를 발급받는다. "
                    + "이 orderId로 Toss SDK를 초기화하고, 결제 완료 후 /api/wallet/toss-charge에 전달한다.")
    @PostMapping("/toss-charge/prepare")
    public ResponseEntity<ApiResponse<TossChargeOrderResponse>> tossPrepare(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody TossChargeOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(tossChargeService.prepare(memberId, request)));
    }

    @Operation(summary = "TossPayments 결제 승인으로 지갑 충전",
            description = "Toss 결제창에서 카드 결제를 마친 뒤 successUrl로 받은 paymentKey/orderId/amount를 "
                    + "전달하면, 서버가 Toss에 최종 승인을 요청하고 승인된 금액만큼 지갑을 충전한다.")
    @PostMapping("/toss-charge")
    public ResponseEntity<ApiResponse<WalletResponse>> tossCharge(@AuthenticationPrincipal String memberId,
                                                                  @Valid @RequestBody TossChargeRequest request) {
        return ResponseEntity.ok(ApiResponse.success(tossChargeService.charge(memberId, request)));
    }
}
