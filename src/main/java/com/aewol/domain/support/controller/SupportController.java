package com.aewol.domain.support.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.support.service.SupportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@Tag(name = "Support", description = "지자체 지원사업 API")
@RestController
@RequestMapping("/api/support")
@RequiredArgsConstructor
public class SupportController {

    private final SupportService supportService;

    @Operation(summary = "지원사업 목록 (지역별)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(@RequestParam(required = false) String region) {
        return ResponseEntity.ok(ApiResponse.success(supportService.getPrograms(region)));
    }

    @Operation(summary = "내 지역 맞춤 지원사업")
    @GetMapping("/matched")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> matched(@AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(supportService.getMatchedPrograms(memberId)));
    }
}
