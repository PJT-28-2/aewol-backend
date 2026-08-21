package com.aewol.domain.explore.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.explore.dto.ExplorePageResponse;
import com.aewol.domain.explore.dto.PetPublicProfileResponse;
import com.aewol.domain.explore.service.ExploreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 멍스타그램 탐색.
 *
 * <p>공개된 일기만 다루지만 <b>로그인은 요구한다</b>. SecurityConfig에 permitAll을 넣지
 * 않는다. 미인증 공개는 신고·차단이 자리잡은 뒤에 따로 판단할 일이다.
 */
@Tag(name = "Explore", description = "멍스타그램 탐색 API")
@RestController
@RequestMapping("/api/explore")
@RequiredArgsConstructor
public class ExploreController {

    private final ExploreService exploreService;

    @Operation(summary = "탐색 피드 조회", description = "공개된 일기를 최신순으로 준다. 커서 페이징.")
    @GetMapping("/diaries")
    public ResponseEntity<ApiResponse<ExplorePageResponse>> getExploreFeed(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "0") int size) {
        return ResponseEntity.ok(ApiResponse.success(exploreService.getExploreFeed(cursor, size)));
    }

    @Operation(summary = "반려동물 계정의 공개 게시물")
    @GetMapping("/pets/{petId}/diaries")
    public ResponseEntity<ApiResponse<ExplorePageResponse>> getPetPosts(
            @PathVariable String petId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "0") int size) {
        return ResponseEntity.ok(ApiResponse.success(exploreService.getPetPosts(petId, cursor, size)));
    }

    @Operation(summary = "반려동물 계정 프로필", description = "사람 정보는 포함하지 않는다.")
    @GetMapping("/pets/{petId}/profile")
    public ResponseEntity<ApiResponse<PetPublicProfileResponse>> getPetProfile(
            @PathVariable String petId) {
        return ResponseEntity.ok(ApiResponse.success(exploreService.getPetProfile(petId)));
    }
}
