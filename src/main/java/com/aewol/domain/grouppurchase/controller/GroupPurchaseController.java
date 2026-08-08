package com.aewol.domain.grouppurchase.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseCreateRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseImageUploadResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseJoinRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseJoinResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseResponse;
import com.aewol.domain.grouppurchase.service.GroupPurchaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import javax.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "GroupPurchase", description = "공동구매 API")
@RestController
@RequestMapping("/api/group-purchase")
@RequiredArgsConstructor
@Validated
public class GroupPurchaseController {

    private final GroupPurchaseService groupPurchaseService;

    @Operation(summary = "공동구매 게시글 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<GroupPurchaseListResponse>> list(
            @AuthenticationPrincipal String memberId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(ApiResponse.success("공동구매 목록 조회 성공",
                groupPurchaseService.list(memberId, status, keyword, category, page, size)));
    }

    @Operation(summary = "공동구매 생성")
    @PostMapping
    public ResponseEntity<ApiResponse<GroupPurchaseResponse>> create(@AuthenticationPrincipal String memberId,
                                                                       @Valid @RequestBody GroupPurchaseCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(groupPurchaseService.create(memberId, request)));
    }

    @Operation(summary = "공동구매 게시글 상세보기")
    @GetMapping("/{gpId}")
    public ResponseEntity<ApiResponse<GroupPurchaseResponse>> detail(@PathVariable String gpId) {
        return ResponseEntity.ok(ApiResponse.success("공동구매 상세 조회 성공", groupPurchaseService.getDetail(gpId)));
    }

    @Operation(summary = "공동구매 참여")
    @PostMapping("/{gpId}/join")
    public ResponseEntity<ApiResponse<GroupPurchaseJoinResponse>> join(@AuthenticationPrincipal String memberId,
                                                   @PathVariable String gpId,
                                                   @RequestParam @Positive int quantity,
                                                   @Valid @RequestBody GroupPurchaseJoinRequest request) {
        return ResponseEntity.ok(ApiResponse.success("공동구매 참여가 완료되었습니다.",
                groupPurchaseService.join(memberId, gpId, quantity, request)));
    }

    @Operation(summary = "공동구매 이미지 업로드")
    @PostMapping("/images")
    public ResponseEntity<ApiResponse<GroupPurchaseImageUploadResponse>> uploadImage(
            @RequestParam("image") MultipartFile image) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(groupPurchaseService.uploadImage(image)));
    }
}
