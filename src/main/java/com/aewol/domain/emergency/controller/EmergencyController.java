package com.aewol.domain.emergency.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.emergency.service.EmergencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@Tag(name = "Emergency", description = "응급 병원 API")
@RestController
@RequestMapping("/api/emergency")
@RequiredArgsConstructor
public class EmergencyController {

    private final EmergencyService emergencyService;

    @Operation(summary = "주변 응급 병원 검색")
    @GetMapping("/hospitals")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> searchNearby(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(defaultValue = "5") double radiusKm) {
        return ResponseEntity.ok(ApiResponse.success(emergencyService.searchNearby(latitude, longitude, radiusKm)));
    }

    @Operation(summary = "24시 병원 목록")
    @GetMapping("/hospitals/24h")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> get24hHospitals() {
        return ResponseEntity.ok(ApiResponse.success(emergencyService.get24hHospitals()));
    }
}
