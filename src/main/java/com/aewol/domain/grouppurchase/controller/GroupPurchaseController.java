package com.aewol.domain.grouppurchase.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseCancelResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseCreateRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseImageUploadResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseJoinRequest;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseJoinResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseLeaveResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchasePasswordRequest;
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

    /**
     * 배포 전환 기간 호환(P1 리뷰 반영): page는 곧 제거될 파라미터다. cursor를 아직 모르는
     * 구 프론트가 계속 page를 보내는 동안 백엔드가 이를 무시하면 cursor가 항상 없어 첫
     * 페이지만 반복 조회되므로, 당분간 둘 다 받는다 — cursor가 있으면 그쪽을 우선한다.
     * 프론트 배포가 끝나면 page 파라미터와 GroupPurchaseService의 legacyPage 오버로드를
     * 함께 제거한다.
     */
    @Operation(summary = "공동구매 게시글 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<GroupPurchaseListResponse>> list(
            @AuthenticationPrincipal String memberId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(ApiResponse.success("공동구매 목록 조회 성공",
                groupPurchaseService.list(memberId, status, keyword, category, cursor, page, size)));
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
     * 지갑 출금(WalletWithdrawalService)과 동일하게, 화면의 간편 비밀번호 사전 확인 결과를 신뢰하지 않고
     * 서비스가 처리 직전에 SimplePasswordVerificationService로 다시 검증한다.
     */
    @Operation(summary = "공동구매 참여 취소 (일반 유저 전용)")
    @PostMapping("/{gpId}/leave")
    public ResponseEntity<ApiResponse<GroupPurchaseLeaveResponse>> leave(@AuthenticationPrincipal String memberId,
                                                                            @PathVariable String gpId,
                                                                            @Valid @RequestBody GroupPurchasePasswordRequest request) {
        return ResponseEntity.ok(ApiResponse.success("공동구매 참여가 취소되었습니다.",
                groupPurchaseService.leave(memberId, gpId, request.getPassword())));
    }

    /**
     * 관리자(role=ADMIN) 전용. SecurityConfig에서 POST /api/group-purchase/{gpId}/cancel에 ROLE_ADMIN을 요구한다.
     * leave()와 달리 게시글 전체를 취소하며, 이미 결제한 참여자 전원을 함께 환불한다. OPEN(진행중) 상태에서만 가능하다.
     * leave()와 동일하게 호출한 관리자 본인의 간편 비밀번호를 처리 직전에 재검증한다.
     */
    @Operation(summary = "공동구매 취소 (작성자 전용)")
    @PostMapping("/{gpId}/cancel")
    public ResponseEntity<ApiResponse<GroupPurchaseCancelResponse>> cancel(@AuthenticationPrincipal String memberId,
                                                                              @PathVariable String gpId,
                                                                              @Valid @RequestBody GroupPurchasePasswordRequest request) {
        return ResponseEntity.ok(ApiResponse.success("공동구매가 취소되었습니다.",
                groupPurchaseService.cancel(memberId, gpId, request.getPassword())));
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
