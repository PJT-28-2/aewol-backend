package com.aewol.domain.emergency.controller;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.response.ApiResponse;
import com.aewol.domain.emergency.service.HospitalSeedRunner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 동물병원 공공데이터 시딩 수동 실행. 외부 API를 호출하고 테이블을 대량 갱신하므로 관리자 전용이다.
 * ({@code /api/admin/**}은 SecurityConfig에서 ROLE_ADMIN을 요구한다)
 *
 * <p>평시 트리거는 {@code HospitalSeedJob}의 월 1회 배치(매월 1일 03:00)이고, 이 엔드포인트는
 * 개발·시연 환경에서 즉시 데이터를 채우거나 배치 실패를 복구할 때 쓴다.
 */
@Tag(name = "Admin - Emergency", description = "동물병원 데이터 시딩 (관리자)")
@RestController
@RequestMapping("/api/admin/emergency/hospitals")
@RequiredArgsConstructor
public class HospitalSeedController {

    private final HospitalSeedRunner hospitalSeedRunner;

    /**
     * 완료를 기다리지 않고 202로 응답한다 — 전량 시딩은 수 분이 걸려 동기 응답 시 리버스 프록시
     * 타임아웃에 걸린다. upsert/삭제/skip 건수는 {@code [HospitalSeed]} 로그로 확인한다.
     */
    @Operation(summary = "동물병원 공공데이터 시딩 수동 실행 (비동기)")
    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<Void>> sync() {
        switch (hospitalSeedRunner.start()) {
            case ALREADY_RUNNING -> throw BusinessException.conflict("동물병원 데이터 시딩이 이미 실행 중입니다.");
            case NOT_CONFIGURED -> throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "동물병원 공공데이터 service-key가 설정되지 않아 시딩을 실행할 수 없습니다.");
            case STARTED -> { /* 아래에서 202 응답 */ }
        }
        return ResponseEntity.accepted().body(ApiResponse.accepted(
                "동물병원 데이터 시딩을 시작했습니다. 진행 상황은 서버 로그를 확인하세요."));
    }
}
