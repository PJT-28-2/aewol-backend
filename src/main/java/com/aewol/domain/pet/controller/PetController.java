package com.aewol.domain.pet.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.pet.dto.PetCreateRequest;
import com.aewol.domain.pet.dto.PetDocumentResponse;
import com.aewol.domain.pet.dto.PetResponse;
import com.aewol.domain.pet.service.PetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
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
    public ResponseEntity<ApiResponse<PetResponse>> getPet(@AuthenticationPrincipal String memberId,
                                                            @PathVariable String petId) {
        return ResponseEntity.ok(ApiResponse.success(petService.getPet(memberId, petId)));
    }

    @Operation(summary = "반려동물 정보 수정")
    @PutMapping("/{petId}")
    public ResponseEntity<ApiResponse<Void>> updatePet(@AuthenticationPrincipal String memberId,
                                                        @PathVariable String petId,
                                                        @Valid @RequestBody PetCreateRequest request) {
        petService.updatePet(memberId, petId, request);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Operation(summary = "반려동물 비활성화")
    @DeleteMapping("/{petId}")
    public ResponseEntity<ApiResponse<Void>> deletePet(@AuthenticationPrincipal String memberId,
                                                        @PathVariable String petId) {
        petService.deactivatePet(memberId, petId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Operation(summary = "접종증명서 업로드")
    @PostMapping(value = "/{petId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<PetDocumentResponse>> uploadVaccinationDocument(
            @AuthenticationPrincipal String memberId,
            @PathVariable String petId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "issuedDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedDate) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(
                        petService.uploadVaccinationDocument(memberId, petId, file, issuedDate)));
    }

    @Operation(summary = "반려동물 문서 목록 조회")
    @GetMapping("/{petId}/documents")
    public ResponseEntity<ApiResponse<List<PetDocumentResponse>>> getPetDocuments(
            @AuthenticationPrincipal String memberId,
            @PathVariable String petId) {
        return ResponseEntity.ok(ApiResponse.success(petService.getPetDocuments(memberId, petId)));
    }

    @Operation(summary = "반려동물 문서 삭제")
    @DeleteMapping("/{petId}/documents/{docId}")
    public ResponseEntity<ApiResponse<Void>> deletePetDocument(
            @AuthenticationPrincipal String memberId,
            @PathVariable String petId,
            @PathVariable String docId) {
        petService.deletePetDocument(memberId, petId, docId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
