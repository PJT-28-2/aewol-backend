package com.aewol.domain.bank.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.bank.dto.BankResponse;
import com.aewol.domain.bank.mapper.BankMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "Bank", description = "은행 목록 API")
@RestController
@RequestMapping("/api/banks")
@RequiredArgsConstructor
public class BankController {

    private final BankMapper bankMapper;

    @Operation(summary = "은행 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<List<BankResponse>>> getBanks() {
        List<BankResponse> banks = bankMapper.findAll().stream()
                .map(b -> BankResponse.builder()
                        .bankCode((String) b.get("bank_code"))
                        .bankName((String) b.get("bank_name"))
                        .build())
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(banks));
    }
}
