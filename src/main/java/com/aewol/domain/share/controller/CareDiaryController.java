package com.aewol.domain.share.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.share.dto.CareDiaryResponse;
import com.aewol.domain.share.dto.CareDiaryUpdateRequest;
import com.aewol.domain.share.dto.CareDiaryVisibilityRequest;
import com.aewol.domain.share.service.CareDiaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "ShareDiary", description = "공동육아 일기 API")
@RestController
@RequestMapping("/api/share")
@RequiredArgsConstructor
public class CareDiaryController {

    private final CareDiaryService careDiaryService;

    @Operation(summary = "공동육아 일기 작성")
    @PostMapping(value = "/{petId}/diaries", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CareDiaryResponse>> create(
            @AuthenticationPrincipal String memberId,
            @PathVariable String petId,
            @RequestParam("diaryDate") String diaryDate,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "image", required = false) MultipartFile image) {
        return ResponseEntity.ok(ApiResponse.success(
                careDiaryService.create(memberId, petId, diaryDate, content, image)));
    }

    @Operation(summary = "월별 공동육아 일기 목록")
    @GetMapping("/{petId}/diaries")
    public ResponseEntity<ApiResponse<List<CareDiaryResponse>>> getMonthly(
            @AuthenticationPrincipal String memberId,
            @PathVariable String petId,
            @RequestParam(value = "yearMonth", required = false) String yearMonth) {
        return ResponseEntity.ok(ApiResponse.success(
                careDiaryService.getMonthly(memberId, petId, yearMonth)));
    }

    @Operation(summary = "공동육아 일기 상세")
    @GetMapping("/diaries/{diaryId}")
    public ResponseEntity<ApiResponse<CareDiaryResponse>> getDetail(
            @AuthenticationPrincipal String memberId,
            @PathVariable String diaryId) {
        return ResponseEntity.ok(ApiResponse.success(careDiaryService.getDetail(memberId, diaryId)));
    }

    @Operation(summary = "공동육아 일기 수정")
    @PutMapping("/diaries/{diaryId}")
    public ResponseEntity<ApiResponse<CareDiaryResponse>> update(
            @AuthenticationPrincipal String memberId,
            @PathVariable String diaryId,
            @Valid @RequestBody CareDiaryUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                careDiaryService.update(memberId, diaryId, request)));
    }

    @Operation(summary = "공동육아 일기 공개/비공개 전환",
            description = "공개는 작성자만, 비공개는 작성자 또는 대표 보호자가 할 수 있다.")
    @PatchMapping("/diaries/{diaryId}/visibility")
    public ResponseEntity<ApiResponse<CareDiaryResponse>> changeVisibility(
            @AuthenticationPrincipal String memberId,
            @PathVariable String diaryId,
            @Valid @RequestBody CareDiaryVisibilityRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                careDiaryService.changeVisibility(memberId, diaryId, request)));
    }

    @Operation(summary = "공동육아 일기 삭제")
    @DeleteMapping("/diaries/{diaryId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal String memberId,
            @PathVariable String diaryId) {
        careDiaryService.delete(memberId, diaryId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
