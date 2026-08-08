package com.aewol.domain.emergency.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.emergency.dto.HospitalDetailResponse;
import com.aewol.domain.emergency.dto.HospitalResponse;
import com.aewol.domain.emergency.service.EmergencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name = "Emergency", description = "응급 병원 API")
@RestController
@RequestMapping("/api/emergency")
@RequiredArgsConstructor
@Validated
public class EmergencyController {

    private final EmergencyService emergencyService;

    @Operation(summary = "주변 병원 검색 (24시간 영업 필터 옵션)")
    @GetMapping("/hospitals")
    public ResponseEntity<ApiResponse<List<HospitalResponse>>> searchNearby(
            @RequestParam
            @DecimalMin(value = "-90.0", message = "latitude는 -90 이상이어야 합니다.")
            @DecimalMax(value = "90.0", message = "latitude는 90 이하여야 합니다.")
            double latitude,
            @RequestParam
            @DecimalMin(value = "-180.0", message = "longitude는 -180 이상이어야 합니다.")
            @DecimalMax(value = "180.0", message = "longitude는 180 이하여야 합니다.")
            double longitude,
            @RequestParam(defaultValue = "5")
            @DecimalMin(value = "0.0", inclusive = false, message = "radiusKm은 0보다 커야 합니다.")
            double radiusKm,
            @RequestParam(defaultValue = "false") boolean is24h) {
        return ResponseEntity.ok(ApiResponse.success(
                emergencyService.searchNearby(latitude, longitude, radiusKm, is24h)));
    }

    @Operation(summary = "병원 상세 조회")
    @GetMapping("/hospitals/{hospitalId}")
    public ResponseEntity<ApiResponse<HospitalDetailResponse>> getDetail(
            @PathVariable
            @Min(value = 1, message = "hospitalId는 1 이상이어야 합니다.")
            Long hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(emergencyService.getDetail(hospitalId)));
    }
}
