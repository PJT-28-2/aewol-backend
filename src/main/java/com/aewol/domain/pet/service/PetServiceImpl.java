package com.aewol.domain.pet.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.FileUtil;
import com.aewol.domain.pet.dto.PetCreateRequest;
import com.aewol.domain.pet.dto.PetDocumentResponse;
import com.aewol.domain.pet.dto.PetResponse;
import com.aewol.domain.pet.mapper.PetDocumentMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PetServiceImpl implements PetService {

    private static final String VACCINATION = "VACCINATION";
    private static final String DOCUMENT_SUB_DIR = "pet-documents";
    private static final int MAX_DOCUMENT_NAME_LENGTH = 100;
    private static final Map<String, Set<String>> ALLOWED_FILE_TYPES = Map.of(
            "image/jpeg", Set.of("jpg", "jpeg"),
            "image/png", Set.of("png"),
            "application/pdf", Set.of("pdf")
    );

    private final PetMapper petMapper;
    private final PetDocumentMapper petDocumentMapper;
    private final FileUtil fileUtil;

    @Override
    @Transactional
    public PetResponse createPet(String memberId, PetCreateRequest request) {
        Map<String, Object> pet = new HashMap<>();
        pet.put("memberId", memberId);
        pet.put("name", request.getName());
        pet.put("species", request.getSpecies());
        pet.put("breed", request.getBreed());
        pet.put("birthDate", request.getBirthDate());
        pet.put("gender", request.getGender());
        pet.put("weight", request.getWeight());
        pet.put("neutered", request.getNeutered());
        pet.put("regNumber", request.getRegNumber());
        pet.put("medicalHistory", request.getMedicalHistory());
        petMapper.insert(pet); // pet_id AUTO_INCREMENT

        return getPet(memberId, String.valueOf(pet.get("petId")));
    }

    @Override
    public List<PetResponse> getPetsByMember(String memberId) {
        return petMapper.findByMemberId(memberId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public PetResponse getPet(String memberId, String petId) {
        Map<String, Object> pet = petMapper.findById(petId);
        if (pet == null) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
        if (!isOwner(memberId, pet)) {
            throw BusinessException.forbidden("반려동물을 조회할 권한이 없습니다.");
        }
        return toResponse(pet);
    }

    @Override
    @Transactional
    public void updatePet(String memberId, String petId, PetCreateRequest request) {
        assertOwner(memberId, petId);
        Map<String, Object> pet = new HashMap<>();
        pet.put("petId", petId);
        pet.put("name", request.getName());
        pet.put("species", request.getSpecies());
        pet.put("breed", request.getBreed());
        pet.put("birthDate", request.getBirthDate());
        pet.put("gender", request.getGender());
        pet.put("weight", request.getWeight());
        pet.put("neutered", request.getNeutered());
        pet.put("regNumber", request.getRegNumber());
        pet.put("medicalHistory", request.getMedicalHistory());
        petMapper.update(pet);
    }

    @Override
    @Transactional
    public void deactivatePet(String memberId, String petId) {
        assertOwner(memberId, petId);
        if (petMapper.deactivate(petId, memberId) != 1) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
    }

    @Override
    @Transactional
    public PetDocumentResponse uploadVaccinationDocument(String memberId, String petId,
                                                          MultipartFile file, LocalDate issuedDate) {
        assertOwner(memberId, petId);
        String storageExtension = validateDocument(file);
        String originalFilename = extractOriginalFilename(file);
        Map<String, Object> existing = petDocumentMapper.findByPetIdAndTypeForUpdate(petId, VACCINATION);
        String newFileUrl;
        try {
            newFileUrl = fileUtil.upload(file, DOCUMENT_SUB_DIR, storageExtension);
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "파일 저장에 실패했습니다.");
        }

        Map<String, Object> document = new HashMap<>();
        document.put("petId", petId);
        document.put("docName", originalFilename);
        document.put("docType", VACCINATION);
        document.put("fileUrl", newFileUrl);
        document.put("issuedDate", issuedDate);

        try {
            if (existing == null) {
                petDocumentMapper.insert(document);
            } else {
                document.put("docId", existing.get("doc_id"));
                petDocumentMapper.update(document);
            }
        } catch (RuntimeException e) {
            deleteQuietly(newFileUrl);
            throw e;
        }

        String oldFileUrl = existing == null ? null : (String) existing.get("file_url");
        arrangeFileCleanup(newFileUrl, oldFileUrl);
        return toDocumentResponse(document);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PetDocumentResponse> getPetDocuments(String memberId, String petId) {
        assertOwner(memberId, petId);
        return petDocumentMapper.findByPetId(petId).stream()
                .map(this::toDocumentResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deletePetDocument(String memberId, String petId, String docId) {
        assertOwner(memberId, petId);
        Map<String, Object> document = petDocumentMapper.findByIdAndPetIdForUpdate(docId, petId);
        if (document == null) {
            throw BusinessException.notFound("문서를 찾을 수 없습니다.");
        }
        if (petDocumentMapper.deleteByIdAndPetId(docId, petId) != 1) {
            throw BusinessException.notFound("문서를 찾을 수 없습니다.");
        }
        arrangeDeletedFileCleanup((String) value(document, "file_url", "fileUrl"));
    }

    private String validateDocument(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("업로드할 파일이 없습니다.");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        Set<String> extensions = ALLOWED_FILE_TYPES.get(contentType);
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "";
        if (extensions == null || !extensions.contains(extension)) {
            throw new BusinessException("JPEG, PNG, PDF 파일만 업로드할 수 있습니다.");
        }
        return "image/jpeg".equals(contentType) ? "jpg" : extension;
    }

    private String extractOriginalFilename(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new BusinessException("파일명이 올바르지 않습니다.");
        }

        String normalized = originalFilename.replace('\\', '/');
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (filename.isBlank() || filename.equals(".") || filename.equals("..")
                || filename.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException("파일명이 올바르지 않습니다.");
        }
        if (filename.length() > MAX_DOCUMENT_NAME_LENGTH) {
            throw new BusinessException("파일명은 100자 이하만 사용할 수 있습니다.");
        }
        return filename;
    }

    private void arrangeFileCleanup(String newFileUrl, String oldFileUrl) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (oldFileUrl != null) deleteQuietly(oldFileUrl);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) deleteQuietly(newFileUrl);
                }
            });
        } else if (oldFileUrl != null) {
            deleteQuietly(oldFileUrl);
        }
    }

    private void arrangeDeletedFileCleanup(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteQuietly(fileUrl);
                }
            });
        } else {
            deleteQuietly(fileUrl);
        }
    }

    private void deleteQuietly(String fileUrl) {
        try {
            fileUtil.delete(fileUrl);
        } catch (IOException e) {
            // 파일시스템은 DB 트랜잭션에 참여하지 않으므로 실패를 숨기지 않고 후속 정리가
            // 가능하도록 파일 URL과 예외를 남긴다. 영속 재시도는 별도 이슈에서 처리한다.
            log.warn("반려동물 문서 파일 삭제 실패 - fileUrl: {}", fileUrl, e);
        }
    }

    private PetDocumentResponse toDocumentResponse(Map<String, Object> document) {
        Object issuedDate = value(document, "issued_date", "issuedDate");
        Object docId = value(document, "doc_id", "docId");
        return PetDocumentResponse.builder()
                .docId(docId == null ? null : String.valueOf(docId))
                .petId(String.valueOf(value(document, "pet_id", "petId")))
                .docName((String) value(document, "doc_name", "docName"))
                .docType(String.valueOf(value(document, "doc_type", "docType")))
                .fileUrl((String) value(document, "file_url", "fileUrl"))
                .issuedDate(issuedDate == null ? null : issuedDate.toString())
                .build();
    }

    private static Object value(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) return map.get(key);
        }
        return null;
    }

    private void assertOwner(String memberId, String petId) {
        Map<String, Object> pet = petMapper.findById(petId);
        if (pet == null) throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        if (!isOwner(memberId, pet)) {
            throw BusinessException.forbidden("대표 보호자만 이 작업을 할 수 있습니다.");
        }
    }

    private boolean isOwner(String memberId, Map<String, Object> pet) {
        return Objects.equals(memberId, String.valueOf(pet.get("member_id")));
    }

    private PetResponse toResponse(Map<String, Object> pet) {
        return PetResponse.builder()
                .petId(String.valueOf(pet.get("pet_id")))
                .memberId(String.valueOf(pet.get("member_id")))
                .name((String) pet.get("name"))
                .species((String) pet.get("species"))
                .breed((String) pet.get("breed"))
                .birthDate(pet.get("birth_date") != null ? pet.get("birth_date").toString() : null)
                .gender((String) pet.get("gender"))
                .weight(pet.get("weight") != null ? ((Number) pet.get("weight")).doubleValue() : null)
                .neutered((String) pet.get("neutered"))
                .regNumber((String) pet.get("reg_number"))
                .iconType((String) pet.get("icon_type"))
                .medicalHistory((String) pet.get("medical_history"))
                .profileImg((String) pet.get("profile_img"))
                .isActive(toBool(pet.get("is_active")))
                .build();
    }

    /** TINYINT(1)은 커넥터 설정에 따라 Boolean 또는 Number로 반환된다 */
    private static boolean toBool(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() == 1;
        return false;
    }
}
