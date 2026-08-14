package com.aewol.domain.insurance.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.insurance.dto.ClaimResponse;
import com.aewol.domain.insurance.dto.ProductResponse;
import com.aewol.domain.insurance.dto.SimulationRequest;
import com.aewol.domain.insurance.dto.SimulationResponse;
import com.aewol.domain.insurance.service.ClaimService;
import com.aewol.domain.insurance.service.InsuranceProductService;
import com.aewol.domain.insurance.service.InsuranceSimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "Insurance", description = "보험 시뮬레이션/청구 API")
@RestController
@RequestMapping("/api/insurance")
@RequiredArgsConstructor
@Validated
public class InsuranceController {

    private final InsuranceSimulationService simulationService;
    private final InsuranceProductService productService;
    private final ClaimService claimService;

    @Operation(summary = "보험 시뮬레이터 결과 계산")
    @PostMapping("/simulations")
    public ResponseEntity<ApiResponse<SimulationResponse>> simulate(@AuthenticationPrincipal String memberId,
                                                                      @Valid @RequestBody SimulationRequest request) {
        return ResponseEntity.ok(ApiResponse.success(simulationService.simulate(memberId, request)));
    }

    @Operation(summary = "보험 상품 리스트 조회")
    @GetMapping("/products")
    public ResponseEntity<ApiResponse<List<ProductResponse>>> getProducts(
            @RequestParam
            @NotBlank(message = "petType은 필수입니다.")
            @Pattern(regexp = "DOG|CAT", message = "petType은 DOG 또는 CAT만 가능합니다.")
            String petType,
            @RequestParam(required = false)
            @Min(value = 0, message = "age는 0 이상이어야 합니다.")
            Integer age,
            @RequestParam(required = false, defaultValue = "premium_asc") String sort) {
        return ResponseEntity.ok(ApiResponse.success(productService.getProducts(petType, age, sort)));
    }

    @Operation(summary = "보험 청구 (영수증 OCR)")
    @PostMapping("/claims")
    public ResponseEntity<ApiResponse<ClaimResponse>> createClaim(@AuthenticationPrincipal String memberId,
                                                                    @RequestParam String petId,
                                                                    @RequestParam("receipt") MultipartFile receipt) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(claimService.createClaim(memberId, petId, receipt)));
    }

    @Operation(summary = "청구 확인 및 제출")
    @PostMapping("/claims/{claimId}/confirm")
    public ResponseEntity<ApiResponse<ClaimResponse>> confirmClaim(@AuthenticationPrincipal String memberId,
                                                                     @PathVariable String claimId,
                                                                     @RequestBody(required = false) ClaimResponse correctedData) {
        return ResponseEntity.ok(ApiResponse.success(claimService.confirmClaim(memberId, claimId, correctedData)));
    }

    @Operation(summary = "청구 내역 조회")
    @GetMapping("/claims")
    public ResponseEntity<ApiResponse<List<ClaimResponse>>> getClaims(@AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(claimService.getClaims(memberId)));
    }

    @Operation(summary = "청구 상세 조회")
    @GetMapping("/claims/{claimId}")
    public ResponseEntity<ApiResponse<ClaimResponse>> getClaim(@AuthenticationPrincipal String memberId,
                                                                 @PathVariable String claimId) {
        return ResponseEntity.ok(ApiResponse.success(claimService.getClaim(memberId, claimId)));
    }
}
