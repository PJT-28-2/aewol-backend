package com.aewol.domain.recurring.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.recurring.dto.RecurringCreateRequest;
import com.aewol.domain.recurring.dto.RecurringResponse;
import com.aewol.domain.recurring.service.RecurringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import java.util.List;

@Tag(name = "Recurring", description = "정기결제 API")
@RestController
@RequestMapping("/api/recurring")
@RequiredArgsConstructor
public class RecurringController {

    private final RecurringService recurringService;

    @Operation(summary = "정기결제 목록")
    @GetMapping
    public ResponseEntity<ApiResponse<List<RecurringResponse>>> list(
            @AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(recurringService.getRecurringPayments(memberId)));
    }

    @Operation(summary = "정기결제 등록")
    @PostMapping
    public ResponseEntity<ApiResponse<RecurringResponse>> create(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody RecurringCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(recurringService.createRecurring(memberId, request)));
    }

    @Operation(summary = "정기결제 해지")
    @DeleteMapping("/{recurringId}")
    public ResponseEntity<ApiResponse<Void>> cancel(@AuthenticationPrincipal String memberId,
                                                     @PathVariable String recurringId) {
        recurringService.cancelRecurring(memberId, recurringId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
