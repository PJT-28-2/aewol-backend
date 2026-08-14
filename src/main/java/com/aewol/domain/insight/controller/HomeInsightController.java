package com.aewol.domain.insight.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.insight.dto.HomeInsightResponse;
import com.aewol.domain.insight.service.HomeInsightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "홈")
@RestController
@RequestMapping("/api/home")
@RequiredArgsConstructor
public class HomeInsightController {

    private final HomeInsightService homeInsightService;

    @Operation(summary = "홈 AI 인사이트 카드",
            description = "지원금·지출·공동육아·짜투리지갑 카드를 돌려준다. 보여줄 데이터가 없는 카드는 빠진다.")
    @GetMapping("/insights")
    public ResponseEntity<ApiResponse<List<HomeInsightResponse>>> getInsights(
            @AuthenticationPrincipal String memberId,
            @RequestParam(required = false) String petId) {
        return ResponseEntity.ok(ApiResponse.success(homeInsightService.getCards(memberId, petId)));
    }
}
