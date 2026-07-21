package com.aewol.domain.insurance.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.insurance.dto.ClaimResponse;
import com.aewol.domain.insurance.dto.SimulationRequest;
import com.aewol.domain.insurance.dto.SimulationResponse;
import com.aewol.domain.insurance.service.ClaimService;
import com.aewol.domain.insurance.service.InsuranceSimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "Insurance", description = "보험 시뮬레이션/청구 API")
@RestController
@RequestMapping("/api/insurance")
@RequiredArgsConstructor
public class InsuranceController {

    private final InsuranceSimulationService simulationService;
    private final ClaimService claimService;

    @Operation(summary = "보험 손익분기 시뮬레이션")
    @PostMapping("/simulate")
    public ResponseEntity<ApiResponse<SimulationResponse>> simulate(@Valid @RequestBody SimulationRequest request) {
        return ResponseEntity.ok(ApiResponse.success(simulationService.simulate(request)));
    }

    @Operation(summary = "보험 청구 (영수증 OCR)")
    @PostMapping("/claim")
    public ResponseEntity<ApiResponse<ClaimResponse>> createClaim(@AuthenticationPrincipal String memberId,
                                                                    @RequestParam String petId,
                                                                    @RequestParam("receipt") MultipartFile receipt) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(claimService.createClaim(memberId, petId, receipt)));
    }

    @Operation(summary = "청구 확인 및 제출")
    @PutMapping("/claims/{claimId}/confirm")
    public ResponseEntity<ApiResponse<ClaimResponse>> confirmClaim(@PathVariable String claimId,
                                                                     @RequestBody ClaimResponse correctedData) {
        return ResponseEntity.ok(ApiResponse.success(claimService.confirmClaim(claimId, correctedData)));
    }

    @Operation(summary = "청구 내역 조회")
    @GetMapping("/claims")
    public ResponseEntity<ApiResponse<List<ClaimResponse>>> getClaims(@AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(claimService.getClaims(memberId)));
    }
}
