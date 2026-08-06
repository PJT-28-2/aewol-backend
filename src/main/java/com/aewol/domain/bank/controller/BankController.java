package com.aewol.domain.bank.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.bank.dto.BankResponse;
import com.aewol.domain.bank.service.BankService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Bank", description = "은행 마스터 API")
@RestController
@RequestMapping("/api/banks")
@RequiredArgsConstructor
public class BankController {

    private final BankService bankService;

    @Operation(summary = "은행 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<List<BankResponse>>> getBanks() {
        return ResponseEntity.ok(ApiResponse.success(bankService.getBanks()));
    }
}
