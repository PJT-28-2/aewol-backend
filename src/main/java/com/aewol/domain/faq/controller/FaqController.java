package com.aewol.domain.faq.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.faq.dto.FaqDetailResponse;
import com.aewol.domain.faq.dto.FaqResponse;
import com.aewol.domain.faq.service.FaqService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BankController와 동일하게, 로그인 여부는 Spring Security 필터에서 강제되고 memberId 값 자체는 조회 로직에 필요 없어 파라미터로 받지 않는다. */
@Tag(name = "FAQ", description = "자주 묻는 질문 API")
@RestController
@RequestMapping("/api/support/faqs")
@RequiredArgsConstructor
public class FaqController {

    private final FaqService faqService;

    @Operation(summary = "FAQ 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<List<FaqResponse>>> getFaqs(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(ApiResponse.success(faqService.getFaqs(category, keyword)));
    }

    @Operation(summary = "FAQ 상세 조회")
    @GetMapping("/{faqId}")
    public ResponseEntity<ApiResponse<FaqDetailResponse>> getFaq(@PathVariable Long faqId) {
        return ResponseEntity.ok(ApiResponse.success(faqService.getFaq(faqId)));
    }
}
