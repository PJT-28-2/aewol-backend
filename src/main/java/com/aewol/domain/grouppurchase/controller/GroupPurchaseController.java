package com.aewol.domain.grouppurchase.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.grouppurchase.service.GroupPurchaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@Tag(name = "GroupPurchase", description = "공동구매 API")
@RestController
@RequestMapping("/api/group-purchase")
@RequiredArgsConstructor
public class GroupPurchaseController {

    private final GroupPurchaseService groupPurchaseService;

    @Operation(summary = "공동구매 목록")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        return ResponseEntity.ok(ApiResponse.success(groupPurchaseService.list()));
    }

    @Operation(summary = "공동구매 생성")
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@AuthenticationPrincipal String memberId,
                                                                     @RequestBody Map<String, Object> request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(groupPurchaseService.create(memberId, request)));
    }

    @Operation(summary = "공동구매 상세")
    @GetMapping("/{gpId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> detail(@PathVariable String gpId) {
        return ResponseEntity.ok(ApiResponse.success(groupPurchaseService.getDetail(gpId)));
    }

    @Operation(summary = "공동구매 참여")
    @PostMapping("/{gpId}/join")
    public ResponseEntity<ApiResponse<Void>> join(@AuthenticationPrincipal String memberId,
                                                   @PathVariable String gpId,
                                                   @RequestParam(defaultValue = "1") int quantity) {
        groupPurchaseService.join(memberId, gpId, quantity);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
