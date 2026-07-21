package com.aewol.domain.pet.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.pet.dto.PetCreateRequest;
import com.aewol.domain.pet.dto.PetResponse;
import com.aewol.domain.pet.service.PetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Pet", description = "반려동물 API")
@RestController
@RequestMapping("/api/pets")
@RequiredArgsConstructor
public class PetController {

    private final PetService petService;

    @Operation(summary = "반려동물 등록")
    @PostMapping
    public ResponseEntity<ApiResponse<PetResponse>> createPet(@AuthenticationPrincipal String memberId,
                                                               @Valid @RequestBody PetCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(petService.createPet(memberId, request)));
    }

    @Operation(summary = "내 반려동물 목록")
    @GetMapping
    public ResponseEntity<ApiResponse<List<PetResponse>>> getMyPets(@AuthenticationPrincipal String memberId) {
        return ResponseEntity.ok(ApiResponse.success(petService.getPetsByMember(memberId)));
    }

    @Operation(summary = "반려동물 상세")
    @GetMapping("/{petId}")
    public ResponseEntity<ApiResponse<PetResponse>> getPet(@PathVariable String petId) {
        return ResponseEntity.ok(ApiResponse.success(petService.getPet(petId)));
    }

    @Operation(summary = "반려동물 정보 수정")
    @PutMapping("/{petId}")
    public ResponseEntity<ApiResponse<Void>> updatePet(@PathVariable String petId,
                                                        @Valid @RequestBody PetCreateRequest request) {
        petService.updatePet(petId, request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Operation(summary = "반려동물 비활성화")
    @DeleteMapping("/{petId}")
    public ResponseEntity<ApiResponse<Void>> deletePet(@PathVariable String petId) {
        petService.deactivatePet(petId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
