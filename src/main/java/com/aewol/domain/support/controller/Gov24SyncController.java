package com.aewol.domain.support.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.support.service.Gov24SyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정부24 동기화 수동 실행. 외부 API를 호출하므로 관리자 전용이다.
 * ({@code /api/admin/**}은 SecurityConfig에서 ROLE_ADMIN을 요구한다)
 */
@Tag(name = "Admin - Gov24", description = "정부24 공공서비스 동기화 (관리자)")
@RestController
@RequestMapping("/api/admin/gov24")
@RequiredArgsConstructor
public class Gov24SyncController {

    private final Gov24SyncService gov24SyncService;

    @Operation(summary = "반려동물 공공지원사업 동기화 수동 실행")
    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sync() {
        int curated = gov24SyncService.syncPetSupportPrograms();
        return ResponseEntity.ok(ApiResponse.success(Map.of("curatedCount", curated)));
    }
}
