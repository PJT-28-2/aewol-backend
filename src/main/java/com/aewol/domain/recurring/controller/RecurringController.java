package com.aewol.domain.recurring.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.recurring.service.RecurringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@Tag(name = "Recurring", description = "정기결제 API")
@RestController
@RequestMapping("/api/recurring")
@RequiredArgsConstructor
public class RecurringController {

    private final RecurringService recurringService;

    @Operation(summary = "정기결제 목록")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(@AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(recurringService.getRecurringPayments(memberId)));
    }

    @Operation(summary = "정기결제 등록")
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@AuthenticationPrincipal String memberId,
                                                                     @RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.created(recurringService.createRecurring(memberId, request)));
    }

    @Operation(summary = "정기결제 해지")
    @DeleteMapping("/{recurringId}")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable String recurringId) {
        recurringService.cancelRecurring(recurringId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
