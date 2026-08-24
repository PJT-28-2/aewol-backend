package com.aewol.domain.pet.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.storage.FileStorage;
import com.aewol.common.util.DateTimeUtil;
import com.aewol.domain.pet.dto.PetCreateRequest;
import com.aewol.domain.pet.dto.PetDocumentResponse;
import com.aewol.domain.pet.dto.PetRegistrationVerifyRequest;
import com.aewol.domain.pet.dto.PetResponse;
import com.aewol.domain.insurance.mapper.InsuranceMapper;
import com.aewol.domain.pet.mapper.PetDocumentMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.recurring.mapper.RecurringMapper;
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
    private static final String MEDICAL_CONFIRMATION = "MEDICAL_CONFIRMATION";
    private static final String REGISTRATION = "REGISTRATION";
    private static final Set<String> UPLOADABLE_DOCUMENT_TYPES =
            Set.of(VACCINATION, MEDICAL_CONFIRMATION);
    private static final String DOCUMENT_SUB_DIR = "pet-documents";
    private static final int MAX_DOCUMENT_NAME_LENGTH = 100;
    private static final Set<String> ALLOWED_DOCUMENT_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "webp", "pdf");
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final byte[] PDF_SIGNATURE = {0x25, 0x50, 0x44, 0x46, 0x2D};
    // WEBP는 "RIFF"(0~3바이트) + 파일 크기(4~7바이트, 가변값이라 검증 대상 아님) + "WEBP"(8~11바이트)
    // 구조라, 다른 포맷처럼 앞에서부터 이어지는 고정 바이트열 하나로는 못 걸러낸다 — 두 구간을
    // 따로 검사해야 한다.
    private static final byte[] WEBP_RIFF_SIGNATURE = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP_FORMAT_SIGNATURE = {0x57, 0x45, 0x42, 0x50};
    private static final int WEBP_FORMAT_SIGNATURE_OFFSET = 8;
    private static final int SIGNATURE_HEADER_LENGTH = WEBP_FORMAT_SIGNATURE_OFFSET + WEBP_FORMAT_SIGNATURE.length;

    private final PetMapper petMapper;
    private final PetDocumentMapper petDocumentMapper;
    private final FileStorage fileStorage;
    private final PetRegistrationService petRegistrationService;
    private final InsuranceMapper insuranceMapper;
    private final RecurringMapper recurringMapper;

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
        pet.put("medicalHistory", request.getMedicalHistory());
        pet.put("instagramId", normalizeInstagramId(request.getInstagramId()));
        petMapper.insert(pet); // pet_id AUTO_INCREMENT

        String petId = String.valueOf(pet.get("petId"));
        if (hasText(request.getRegNumber())) {
            petRegistrationService.verify(memberId, petId,
                    registrationRequest(request, request.getRegNumber()));
        }

        return getPet(memberId, petId);
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
        if (!isOwner(memberId, pet) && !petMapper.hasSharedAccess(petId, memberId)) {
            throw BusinessException.forbidden("반려동물을 조회할 권한이 없습니다.");
        }
        return toResponse(pet);
    }

    @Override
    @Transactional
    public void updatePet(String memberId, String petId, PetCreateRequest request) {
        Map<String, Object> existingPet = assertOwner(memberId, petId);
        String registrationNumberToVerify = registrationNumberToVerify(existingPet, request);
        Map<String, Object> pet = new HashMap<>();
        pet.put("petId", petId);
        pet.put("name", request.getName());
        pet.put("species", request.getSpecies());
        pet.put("breed", request.getBreed());
        pet.put("birthDate", request.getBirthDate());
        pet.put("gender", request.getGender());
        pet.put("weight", request.getWeight());
        pet.put("neutered", request.getNeutered());
        pet.put("medicalHistory", request.getMedicalHistory());
        pet.put("instagramId", normalizeInstagramId(request.getInstagramId()));
        petMapper.update(pet);
        if (registrationNumberToVerify != null) {
            petRegistrationService.verify(memberId, petId,
                    registrationRequest(request, registrationNumberToVerify));
        }
    }

    @Override
    @Transactional
    public void disconnectRegistration(String memberId, String petId) {
        assertOwner(memberId, petId);
        Map<String, Object> document = petDocumentMapper.findByPetIdAndTypeForUpdate(petId, REGISTRATION);
        if (document == null) {
            throw BusinessException.notFound("연동된 동물등록정보를 찾을 수 없습니다.");
        }
        String docId = String.valueOf(document.get("doc_id"));
        petRegistrationService.cancel(memberId, petId, docId);
        if (petDocumentMapper.deleteByIdAndPetId(docId, petId) != 1) {
            throw BusinessException.notFound("동물등록증을 찾을 수 없습니다.");
        }
    }

    /**
     * 반려동물 등록해제(#291): pet 행 자체는 지우지 않는다 — care_diary(ON DELETE CASCADE)와
     * shared_access(ON DELETE NO ACTION)가 pet_id를 참조하고 있어서, pet 행을 하드 삭제하면
     * 공동육아 구성원이 쓴 다이어리가 강제로 함께 삭제되거나 shared_access를 먼저 지워야만
     * 삭제가 가능해져 다른 구성원의 접근 권한이 끊긴다. 그래서 소유자 개인 데이터(등록증/문서/
     * 보험청구/보험시뮬레이션)만 하드 삭제하고, 공동 데이터(care_diary/shared_access)는 그대로 둔다.
     * transaction은 감사 추적성 때문에, recurring_payment는 transaction.recurring_id가 이를
     * ON DELETE NO ACTION으로 참조해 하드 삭제 시 FK 위반이 날 수 있어서 하드 삭제 대신 비활성화한다.
     * reg_number 초기화는 deactivate보다 먼저 해야 한다 — updateRegistrationNumber는
     * is_active = 1 조건으로 매칭하므로, deactivate 이후에 호출하면 0행이 되어 반영되지 않는다.
     */
    @Override
    @Transactional
    public void deactivatePet(String memberId, String petId) {
        assertOwner(memberId, petId);

        for (Map<String, Object> document : petDocumentMapper.findByPetId(petId)) {
            deletePetDocument(memberId, petId, String.valueOf(value(document, "doc_id", "docId")));
        }
        // insurance_claim은 pet_document와 달리 첨부 파일 키를 자체 컬럼(receipt_image_url/
        // claim_document_url)에 들고 있어서, 행을 지우기 전에 먼저 조회해 정리 대상 키를 확보한다.
        for (Map<String, Object> claim : insuranceMapper.findClaimsByPetId(petId)) {
            arrangeDeletedFileCleanup((String) value(claim, "receipt_image_url", "receiptImageUrl"));
            arrangeDeletedFileCleanup((String) value(claim, "claim_document_url", "claimDocumentUrl"));
        }
        insuranceMapper.deleteClaimsByPetId(petId);
        insuranceMapper.deleteSimulationsByPetId(petId);
        recurringMapper.deactivateByPetId(petId);
        if (petMapper.updateRegistrationNumber(petId, memberId, null) != 1) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }

        if (petMapper.deactivate(petId, memberId) != 1) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
    }

    /**
     * 접종증명서/진료확인서는 반려동물+타입당 여러 건이 누적된다 — 업로드할 때마다 새 문서로
     * insert하며, 이전에 올린 문서를 덮어쓰거나 그 파일을 지우지 않는다(개별 삭제는
     * deletePetDocument로 별도 처리). 동물등록증(REGISTRATION)은 이 메서드 대상이 아니고
     * verify()가 자체적으로 1건만 유지하는 별도 upsert 로직을 갖는다.
     */
    @Override
    @Transactional
    public PetDocumentResponse uploadPetDocument(String memberId, String petId, String docType,
                                                 MultipartFile file, LocalDate issuedDate) {
        assertOwner(memberId, petId);
        String normalizedDocType = normalizeDocumentType(docType);
        String storageExtension = validateDocument(file);
        String originalFilename = extractOriginalFilename(file);
        String newFileUrl;
        try {
            newFileUrl = fileStorage.store(file.getBytes(), DOCUMENT_SUB_DIR, storageExtension);
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "파일 저장에 실패했습니다.");
        }

        Map<String, Object> document = new HashMap<>();
        document.put("petId", petId);
        document.put("docName", originalFilename);
        document.put("docType", normalizedDocType);
        document.put("fileUrl", newFileUrl);
        document.put("issuedDate", issuedDate);

        try {
            petDocumentMapper.insert(document);
        } catch (RuntimeException e) {
            deleteQuietly(newFileUrl);
            throw e;
        }

        arrangeUploadFailureCleanup(newFileUrl);
        return toDocumentResponse(document);
    }

    private String normalizeDocumentType(String docType) {
        String normalized = docType == null ? "" : docType.trim().toUpperCase(Locale.ROOT);
        if (!UPLOADABLE_DOCUMENT_TYPES.contains(normalized)) {
            throw new BusinessException(
                    "문서 유형은 VACCINATION 또는 MEDICAL_CONFIRMATION만 가능합니다.");
        }
        return normalized;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PetDocumentResponse> getPetDocuments(String memberId, String petId) {
        assertOwner(memberId, petId);
        return petDocumentMapper.findByPetId(petId).stream()
                .map(this::toDocumentResponse)
                .collect(Collectors.toList());
    }

    /**
     * 문서 상세 조회(증명서 상세). docType에 따라 응답 형태가 다르다 — REGISTRATION은
     * APMS 연동 상세(PetRegistrationResponse), 그 외(VACCINATION/MEDICAL_CONFIRMATION)는
     * 목록과 동일한 얕은 형태(PetDocumentResponse)를 돌려준다.
     */
    @Override
    @Transactional(readOnly = true)
    public Object getPetDocument(String memberId, String petId, String docId) {
        assertOwner(memberId, petId);
        Map<String, Object> document = petDocumentMapper.findByIdAndPetId(docId, petId);
        if (document == null) {
            throw BusinessException.notFound("문서를 찾을 수 없습니다.");
        }
        if (REGISTRATION.equals(value(document, "doc_type", "docType"))) {
            return petRegistrationService.getDetail(memberId, petId, docId);
        }
        return toDocumentResponse(document);
    }

    @Override
    @Transactional
    public void deletePetDocument(String memberId, String petId, String docId) {
        assertOwner(memberId, petId);
        Map<String, Object> document = petDocumentMapper.findByIdAndPetIdForUpdate(docId, petId);
        if (document == null) {
            throw BusinessException.notFound("문서를 찾을 수 없습니다.");
        }
        // REGISTRATION은 pet_registration이 fk_petreg_doc로 이 문서를 참조하고 있어서,
        // 그 행을 먼저 지우지 않으면 pet_document 삭제가 FK 제약 위반으로 실패한다.
        if (REGISTRATION.equals(value(document, "doc_type", "docType"))) {
            petRegistrationService.cancel(memberId, petId, docId);
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
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "";
        if (!ALLOWED_DOCUMENT_EXTENSIONS.contains(extension) || !hasMatchingFileSignature(file, extension)) {
            throw new BusinessException("JPEG, PNG, WEBP, PDF 파일만 업로드할 수 있습니다.");
        }
        return "jpeg".equals(extension) ? "jpg" : extension;
    }

    private boolean hasMatchingFileSignature(MultipartFile file, String extension) {
        try {
            byte[] header = file.getInputStream().readNBytes(SIGNATURE_HEADER_LENGTH);
            if ("jpg".equals(extension) || "jpeg".equals(extension)) {
                return startsWith(header, JPEG_SIGNATURE);
            }
            if ("png".equals(extension)) {
                return startsWith(header, PNG_SIGNATURE);
            }
            if ("webp".equals(extension)) {
                return startsWith(header, WEBP_RIFF_SIGNATURE)
                        && matchesAt(header, WEBP_FORMAT_SIGNATURE_OFFSET, WEBP_FORMAT_SIGNATURE);
            }
            return "pdf".equals(extension) && startsWith(header, PDF_SIGNATURE);
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "파일을 확인하는 중 오류가 발생했습니다.");
        }
    }

    private boolean startsWith(byte[] bytes, byte[] signature) {
        return matchesAt(bytes, 0, signature);
    }

    private boolean matchesAt(byte[] bytes, int offset, byte[] signature) {
        if (bytes.length < offset + signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if (bytes[offset + i] != signature[i]) return false;
        }
        return true;
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

    /** insert 이후 커밋 전에 트랜잭션이 롤백되면(DB 삽입은 되돌아가도 파일시스템 쓰기는 안 되므로) 방금 올린 파일을 지운다. */
    private void arrangeUploadFailureCleanup(String newFileUrl) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) deleteQuietly(newFileUrl);
                }
            });
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
        // 저장소는 DB 트랜잭션에 참여하지 않으므로, 이전 파일 정리에 실패해도 이미 끝난
        // 작업을 되돌리지 않는다. FileStorage 구현체들이 스스로 실패를 삼키긴 하지만
        // 인터페이스가 보장하는 계약은 아니라 호출부에서도 막아 둔다.
        try {
            fileStorage.delete(fileUrl);
        } catch (RuntimeException e) {
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
                .fileUrl(fileStorage.signedUrl((String) value(document, "file_url", "fileUrl")))
                .issuedDate(issuedDate == null ? null : issuedDate.toString())
                .createdAt(DateTimeUtil.toIsoString(value(document, "created_at", "createdAt")))
                .build();
    }

    private static Object value(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) return map.get(key);
        }
        return null;
    }

    private Map<String, Object> assertOwner(String memberId, String petId) {
        Map<String, Object> pet = petMapper.findById(petId);
        if (pet == null) throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        if (!isOwner(memberId, pet)) {
            throw BusinessException.forbidden("대표 보호자만 이 작업을 할 수 있습니다.");
        }
        return pet;
    }

    private String registrationNumberToVerify(Map<String, Object> existingPet, PetCreateRequest request) {
        String existingRegNumber = stringValue(existingPet.get("reg_number"));
        String requestedRegNumber = hasText(request.getRegNumber()) ? request.getRegNumber() : existingRegNumber;
        if (!hasText(requestedRegNumber)) return null;
        if (!Objects.equals(requestedRegNumber, existingRegNumber)) return requestedRegNumber;
        if (hasText(request.getRegistrationOwnerName())) return requestedRegNumber;
        if (!Objects.equals(request.getName(), existingPet.get("name"))) return requestedRegNumber;
        if (!Objects.equals(request.getBirthDate(), stringValue(existingPet.get("birth_date")))) {
            return requestedRegNumber;
        }
        return null;
    }

    private PetRegistrationVerifyRequest registrationRequest(PetCreateRequest request, String regNumber) {
        return new PetRegistrationVerifyRequest(
                regNumber, request.getRegistrationOwnerName(), null);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
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
                .profileImg(fileStorage.signedUrl((String) pet.get("profile_img")))
                // 홈 화면 히어로용 전신 캐릭터. 조회할 때마다 새 서명 URL을 발급하므로
                // 프론트는 이 값을 저장하지 말고 매번 받은 것을 써야 만료로 깨지지 않는다.
                .characterImg(fileStorage.signedUrl((String) pet.get("character_img")))
                .isActive(toBool(pet.get("is_active")))
                .build();
    }

    /** TINYINT(1)은 커넥터 설정에 따라 Boolean 또는 Number로 반환된다 */
    private static boolean toBool(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() == 1;
        return false;
    }

    /**
     * 인스타그램 핸들을 저장 형태로 맞춘다.
     *
     * <p>사용자는 {@code @보리}처럼 앞에 {@code @}를 붙여 적기도 하고 대소문자를 섞어
     * 적기도 한다. 인스타그램 핸들은 대소문자를 구분하지 않으므로, 저장은 {@code @} 없이
     * 소문자로 통일한다. 링크를 만들 때마다 형태가 달라지면 같은 계정이 다르게 보인다.
     *
     * <p>빈 문자열은 null로 바꾼다. 지우려고 비운 것과 처음부터 없는 것을 DB에서 같게 둔다.
     */
    private static String normalizeInstagramId(String rawHandle) {
        if (rawHandle == null) {
            return null;
        }
        String handle = rawHandle.trim();
        if (handle.startsWith("@")) {
            handle = handle.substring(1);
        }
        return handle.isEmpty() ? null : handle.toLowerCase(java.util.Locale.ROOT);
    }
}
