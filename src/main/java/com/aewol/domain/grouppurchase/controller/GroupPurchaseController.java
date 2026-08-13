package com.aewol.domain.grouppurchase.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseCreateRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseImageUploadResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseJoinRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseJoinResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseLeaveResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseMyItemResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseStatusResponse;
import com.aewol.domain.grouppurchase.service.GroupPurchaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
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

    /** 일반 유저(role=USER) 전용. SecurityConfig에서 GET /api/group-purchase/my에 ROLE_USER를 요구한다(관리자의 작성글 관리는 별도 엔드포인트 예정). */
    @Operation(summary = "참여했던 공동구매 게시글 조회(마이페이지, 일반 유저 전용)")
    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<GroupPurchaseMyItemResponse>>> myList(
            @AuthenticationPrincipal String memberId,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.success("나의 공동구매 목록 조회 성공",
                groupPurchaseService.getMyList(memberId, status)));
    }

    /** 관리자(role=ADMIN) 전용. SecurityConfig에서 POST /api/group-purchase에 ROLE_ADMIN을 요구한다. */
    @Operation(summary = "공동구매 생성 (관리자 전용)")
    @PostMapping
    public ResponseEntity<ApiResponse<GroupPurchaseResponse>> create(@AuthenticationPrincipal String memberId,
                                                                       @Valid @RequestBody GroupPurchaseCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(groupPurchaseService.create(memberId, request)));
    }

    @Operation(summary = "공동구매 게시글 상세보기")
    @GetMapping("/{gpId}")
    public ResponseEntity<ApiResponse<GroupPurchaseResponse>> detail(@AuthenticationPrincipal String memberId,
                                                                        @PathVariable String gpId) {
        return ResponseEntity.ok(ApiResponse.success("공동구매 상세 조회 성공", groupPurchaseService.getDetail(memberId, gpId)));
    }

    @Operation(summary = "공동구매 상태 화면")
    @GetMapping("/{gpId}/status")
    public ResponseEntity<ApiResponse<GroupPurchaseStatusResponse>> status(@AuthenticationPrincipal String memberId,
                                                                             @PathVariable String gpId) {
        return ResponseEntity.ok(ApiResponse.success("결제 후 대기 상태 조회 성공",
                groupPurchaseService.getStatus(memberId, gpId)));
    }

    /** 일반 유저(role=USER) 전용. SecurityConfig에서 POST /api/group-purchase/{gpId}/join에 ROLE_USER를 요구한다(관리자는 참여 불가). */
    @Operation(summary = "공동구매 참여 (일반 유저 전용)")
    @PostMapping("/{gpId}/join")
    public ResponseEntity<ApiResponse<GroupPurchaseJoinResponse>> join(@AuthenticationPrincipal String memberId,
                                                   @PathVariable String gpId,
                                                   @RequestParam @Positive int quantity,
                                                   @Valid @RequestBody GroupPurchaseJoinRequest request) {
        return ResponseEntity.ok(ApiResponse.success("공동구매 참여가 완료되었습니다.",
                groupPurchaseService.join(memberId, gpId, quantity, request)));
    }

    /**
     * 일반 유저(role=USER) 전용. SecurityConfig에서 POST /api/group-purchase/{gpId}/leave에 ROLE_USER를 요구한다.
     * OPEN(진행중) 상태에서만 취소 가능하며, 목표 수량 달성(COMPLETED) 이후에는 관리자 문의로만 취소할 수 있다.
     */
    @Operation(summary = "공동구매 참여 취소 (일반 유저 전용)")
    @PostMapping("/{gpId}/leave")
    public ResponseEntity<ApiResponse<GroupPurchaseLeaveResponse>> leave(@AuthenticationPrincipal String memberId,
                                                                            @PathVariable String gpId) {
        return ResponseEntity.ok(ApiResponse.success("공동구매 참여가 취소되었습니다.",
                groupPurchaseService.leave(memberId, gpId)));
    }

    /** 관리자(role=ADMIN) 전용. SecurityConfig에서 POST /api/group-purchase/images에 ROLE_ADMIN을 요구한다. */
    @Operation(summary = "공동구매 이미지 업로드 (관리자 전용)")
    @PostMapping("/images")
    public ResponseEntity<ApiResponse<GroupPurchaseImageUploadResponse>> uploadImage(
            @RequestParam("image") MultipartFile image) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(groupPurchaseService.uploadImage(image)));
    }
}
