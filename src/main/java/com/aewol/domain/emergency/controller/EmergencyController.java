package com.aewol.domain.emergency.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.emergency.dto.HospitalResponse;
import com.aewol.domain.emergency.service.EmergencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name = "Emergency", description = "응급 병원 API")
@RestController
@RequestMapping("/api/emergency")
@RequiredArgsConstructor
public class EmergencyController {

    private final EmergencyService emergencyService;

    @Operation(summary = "주변 병원 검색 (24시간 영업 필터 옵션)")
    @GetMapping("/hospitals")
    public ResponseEntity<ApiResponse<List<HospitalResponse>>> searchNearby(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(defaultValue = "5") double radiusKm,
            @RequestParam(defaultValue = "false") boolean is24h) {
        return ResponseEntity.ok(ApiResponse.success(
                emergencyService.searchNearby(latitude, longitude, radiusKm, is24h)));
    }
}
