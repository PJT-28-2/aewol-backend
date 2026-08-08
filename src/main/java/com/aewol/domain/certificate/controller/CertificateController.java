package com.aewol.domain.certificate.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.certificate.dto.ApmsSyncRequest;
import com.aewol.domain.certificate.dto.PetRegistrationCandidateResponse;
import com.aewol.domain.certificate.service.CertificateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Certificate", description = "증명서 API")
@RestController
@RequestMapping("/api/certificates")
@RequiredArgsConstructor
public class CertificateController {

    private final CertificateService certificateService;

    @Operation(summary = "동물등록증 조회 (APMS 연동)")
    @PostMapping("/registration/sync")
    public ResponseEntity<ApiResponse<List<PetRegistrationCandidateResponse>>> syncRegistration(
            @AuthenticationPrincipal String memberId,
            @Valid @RequestBody ApmsSyncRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "동물등록증 조회 성공", certificateService.syncRegistration(memberId, request)));
    }
}
