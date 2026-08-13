package com.aewol.domain.pet.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.storage.FileStorage;
import com.aewol.domain.pet.dto.PetDocumentDetailResponse;
import com.aewol.domain.pet.dto.PetRegistrationResponse;
import com.aewol.domain.pet.dto.PetRegistrationVerifyRequest;
import com.aewol.domain.pet.mapper.PetDocumentMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.pet.mapper.PetRegistrationMapper;
import com.aewol.external.apms.ApmsClient;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PetRegistrationServiceImpl implements PetRegistrationService {
    private static final String REGISTRATION = "REGISTRATION";

    private final PetMapper petMapper;
    private final PetDocumentMapper petDocumentMapper;
    private final PetRegistrationMapper petRegistrationMapper;
    private final ApmsClient apmsClient;
    private final FileStorage fileStorage;

    @Override
    @Transactional
    public PetRegistrationResponse verify(String memberId, String petId, PetRegistrationVerifyRequest request) {
        Map<String, Object> pet = petMapper.findById(petId);
        if (pet == null) throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        if (!Objects.equals(memberId, String.valueOf(pet.get("member_id")))) {
            throw BusinessException.forbidden("대표 보호자만 이 작업을 할 수 있습니다.");
        }

        String ownerName = trimToNull(request.getUserName());
        String ownerBirth = digits(request.getBirthDate());
        if (ownerName == null && ownerBirth == null) {
            throw new BusinessException("소유자 이름 또는 생년월일을 입력해주세요.");
        }
        if (ownerBirth != null && ownerBirth.length() != 8) {
            throw new BusinessException("생년월일은 yyyyMMdd 형식이어야 합니다.");
        }

        Map<String, Object> external = apmsClient.verifyRegistration(
                request.getRegNumber(), ownerName, ownerBirth);
        if (external == null) {
            throw new BusinessException("일치하는 동물등록정보를 찾을 수 없습니다.");
        }

        Map<String, Object> registration = normalize(external, petId);
        validateExternalResponse(registration);
        validateMatch(pet, registration, request.getRegNumber());
        validateDuplicate(petId, request.getRegNumber());

        Map<String, Object> document = petDocumentMapper.findByPetIdAndTypeForUpdate(petId, REGISTRATION);
        boolean firstVerification = document == null;
        if (firstVerification) {
            document = new HashMap<>();
            document.put("petId", petId);
            document.put("docName", registration.get("name") + " 동물등록증");
            document.put("docType", REGISTRATION);
            document.put("fileUrl", null);
            document.put("issuedDate", parseDate(value(external, "issueDate", "resIssueDate")));
            petDocumentMapper.insert(document);
        } else {
            document.put("petId", petId);
            document.put("docName", registration.get("name") + " 동물등록증");
            document.put("fileUrl", null);
            document.put("issuedDate", parseDate(value(external, "issueDate", "resIssueDate")));
            document.put("docId", value(document, "doc_id", "docId"));
            petDocumentMapper.update(document);
        }

        registration.put("docId", value(document, "doc_id", "docId"));
        try {
            if (firstVerification) {
                petRegistrationMapper.insert(registration);
            } else if (petRegistrationMapper.update(registration) != 1) {
                throw BusinessException.notFound("동물등록정보를 찾을 수 없습니다.");
            }
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("이미 다른 반려동물에 사용 중인 동물등록번호입니다.");
        }
        if (petMapper.updateRegistrationNumber(petId, memberId, request.getRegNumber()) != 1) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
        return response(registration);
    }

    @Override
    public PetDocumentDetailResponse getDocument(String memberId, String petId, String docId) {
        requireOwnedPet(memberId, petId);

        Map<String, Object> document = petDocumentMapper.findByIdAndPetId(docId, petId);
        if (document == null) {
            throw BusinessException.notFound("문서를 찾을 수 없습니다.");
        }

        // 등록증이 아닌 문서(접종증명서 등)는 외부 조회 정보가 없어 registration이 빈다.
        Map<String, Object> registration = REGISTRATION.equals(string(document.get("doc_type")))
                ? petRegistrationMapper.findByDocIdAndPetId(docId, petId)
                : null;

        return PetDocumentDetailResponse.builder()
                .docId(string(document.get("doc_id")))
                .petId(string(document.get("pet_id")))
                .docName(string(document.get("doc_name")))
                .docType(string(document.get("doc_type")))
                // 업로드 파일은 비공개라 만료되는 서명 URL로만 내려간다.
                .fileUrl(fileStorage.signedUrl(string(document.get("file_url"))))
                .issuedDate(string(document.get("issued_date")))
                .createdAt(string(document.get("created_at")))
                .registration(registration == null ? null : fromRow(registration))
                .build();
    }

    @Override
    @Transactional
    public PetRegistrationResponse resync(String memberId, String petId, String docId) {
        requireOwnedPet(memberId, petId);

        Map<String, Object> stored = petRegistrationMapper.findByDocIdAndPetId(docId, petId);
        if (stored == null) {
            throw BusinessException.notFound("동물등록정보를 찾을 수 없습니다.");
        }
        String regNumber = string(stored.get("reg_number"));
        if (regNumber == null) {
            throw new BusinessException("등록번호가 없어 재조회할 수 없습니다.");
        }

        // 이미 한 번 인증해 저장해 둔 등록번호라 소유자 이름·생년월일을 다시 받지 않는다.
        // APMS는 owner_nm이 비면 등록번호만으로 조회한다.
        Map<String, Object> external = apmsClient.findRegistration(regNumber, null)
                .orElseThrow(() -> new BusinessException("동물등록정보를 다시 불러오지 못했습니다."));

        Map<String, Object> registration = normalize(external, petId);
        validateExternalResponse(registration);
        registration.put("docId", docId);
        if (petRegistrationMapper.update(registration) != 1) {
            throw BusinessException.notFound("동물등록정보를 찾을 수 없습니다.");
        }

        // last_synced_at은 update()가 NOW()로 채우므로 갱신된 행을 다시 읽어 내려준다.
        return fromRow(petRegistrationMapper.findByDocIdAndPetId(docId, petId));
    }

    private Map<String, Object> requireOwnedPet(String memberId, String petId) {
        Map<String, Object> pet = petMapper.findById(petId);
        if (pet == null) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
        if (!Objects.equals(memberId, String.valueOf(pet.get("member_id")))) {
            throw BusinessException.forbidden("대표 보호자만 이 작업을 할 수 있습니다.");
        }
        return pet;
    }

    /** DB 행(snake_case)을 응답으로 옮긴다. {@link #response}는 정규화된 map(camelCase)용이다. */
    private PetRegistrationResponse fromRow(Map<String, Object> row) {
        return PetRegistrationResponse.builder()
                .docId(string(row.get("doc_id"))).petId(string(row.get("pet_id")))
                .regNumber(string(row.get("reg_number"))).name(string(row.get("name")))
                .breed(string(row.get("breed"))).gender(string(row.get("gender")))
                .neutered(string(row.get("neutered"))).birthDate(string(row.get("birth_date")))
                .rfidCd(string(row.get("rfid_cd"))).rfidGubun(string(row.get("rfid_gubun")))
                .orgNm(string(row.get("org_nm"))).officeTel(string(row.get("office_tel")))
                .aprGbnNm(string(row.get("apr_gbn_nm"))).regTm(string(row.get("reg_tm")))
                .aprTm(string(row.get("apr_tm"))).lastSyncedAt(string(row.get("last_synced_at")))
                .verified(true).build();
    }

    private void validateDuplicate(String petId, String regNumber) {
        Map<String, Object> existing = petRegistrationMapper.findByRegNumber(regNumber);
        if (existing != null && !existing.isEmpty()
                && !Objects.equals(petId, String.valueOf(existing.get("pet_id")))) {
            throw BusinessException.conflict("이미 다른 반려동물에 사용 중인 동물등록번호입니다.");
        }
    }

    private void validateExternalResponse(Map<String, Object> registration) {
        if (registration.get("regNumber") == null || registration.get("name") == null) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "동물등록정보 응답이 올바르지 않습니다.");
        }
        String approval = string(registration.get("aprGbnNm"));
        if (approval != null && !approval.contains("승인")) {
            throw new BusinessException("승인된 동물등록정보가 아닙니다.");
        }
        if (approval != null && approval.contains("대기")) {
            throw new BusinessException("아직 승인되지 않은 동물등록정보입니다.");
        }
    }

    private void validateMatch(Map<String, Object> pet, Map<String, Object> registration, String requestedRegNumber) {
        if (!Objects.equals(requestedRegNumber, registration.get("regNumber"))) mismatch();
        if (!sameText(pet.get("name"), registration.get("name"))) mismatch();
        if (pet.get("birth_date") != null
                && !Objects.equals(digits(pet.get("birth_date").toString()), digits(string(registration.get("birthDate"))))) {
            mismatch();
        }
        String petGender = normalizeGender(string(pet.get("gender")));
        String registeredGender = string(registration.get("gender"));
        if (petGender != null && registeredGender != null && !petGender.equals(registeredGender)) mismatch();
    }

    private void mismatch() {
        throw new BusinessException("동물등록정보가 반려동물 정보와 일치하지 않습니다.");
    }

    private Map<String, Object> normalize(Map<String, Object> source, String petId) {
        Map<String, Object> result = new HashMap<>();
        result.put("petId", petId);
        result.put("regNumber", first(source, null, "dogRegNo", "regNumber", "resRegNumber"));
        result.put("name", first(source, null, "dogNm", "name", "commName"));
        result.put("breed", first(source, null, "kindNm", "breed", "resKind"));
        result.put("gender", normalizeGender(first(source, null, "sexNm", "gender", "resGender")));
        result.put("neutered", normalizeNeutered(first(source, null, "neuterYn", "neutered", "resNeuterYN")));
        result.put("birthDate", digits(first(source, null, "birthDt", "birthDate", "commBirthDate")));
        result.put("rfidCd", first(source, null, "rfidCd", "resRfidCd"));
        result.put("rfidGubun", first(source, null, "rfidGubun", "resRfidGubun"));
        result.put("orgNm", first(source, null, "orgNm", "resIssueOgzNm"));
        result.put("officeTel", first(source, null, "officeTel", "resPhoneNo"));
        result.put("aprGbnNm", first(source, null, "aprGbNm", "aprGbnNm", "resState"));
        result.put("regTm", parseDateTime(first(source, null, "regTm", "resRegisterDate")));
        result.put("aprTm", parseDateTime(first(source, null, "aprTm")));
        return result;
    }

    private PetRegistrationResponse response(Map<String, Object> row) {
        return PetRegistrationResponse.builder()
                .docId(string(row.get("docId"))).petId(string(row.get("petId")))
                .regNumber(string(row.get("regNumber"))).name(string(row.get("name")))
                .breed(string(row.get("breed"))).gender(string(row.get("gender")))
                .neutered(string(row.get("neutered"))).birthDate(string(row.get("birthDate")))
                .rfidCd(string(row.get("rfidCd"))).rfidGubun(string(row.get("rfidGubun")))
                .orgNm(string(row.get("orgNm"))).officeTel(string(row.get("officeTel")))
                .aprGbnNm(string(row.get("aprGbnNm"))).regTm(string(row.get("regTm")))
                .aprTm(string(row.get("aprTm"))).verified(true).build();
    }

    private static String first(Map<String, Object> map, String fallback, String... keys) {
        Object found = value(map, keys);
        return found == null ? fallback : found.toString();
    }

    private static Object value(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }

    private static boolean sameText(Object left, Object right) {
        if (left == null || right == null) return false;
        return left.toString().replaceAll("\\s+", "").equalsIgnoreCase(right.toString().replaceAll("\\s+", ""));
    }

    private static String normalizeGender(String value) {
        if (value == null) return null;
        String upper = value.trim().toUpperCase(Locale.ROOT);
        if (upper.equals("수컷") || upper.equals("M") || upper.equals("MALE")) return "MALE";
        if (upper.equals("암컷") || upper.equals("F") || upper.equals("FEMALE")) return "FEMALE";
        return upper;
    }

    private static String normalizeNeutered(String value) {
        if (value == null) return null;
        String upper = value.trim().toUpperCase(Locale.ROOT);
        if (upper.equals("O") || upper.equals("Y")) return "Y";
        if (upper.equals("X") || upper.equals("N")) return "N";
        return upper;
    }

    private static String digits(String value) {
        if (value == null) return null;
        String digits = value.replaceAll("\\D", "");
        return digits.isBlank() ? null : digits;
    }

    private static String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static LocalDate parseDate(Object value) {
        String digits = digits(string(value));
        if (digits == null || digits.length() < 8) return null;
        try { return LocalDate.parse(digits.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE); }
        catch (DateTimeParseException e) { return null; }
    }

    private static LocalDateTime parseDateTime(String value) {
        String digits = digits(value);
        if (digits == null) return null;
        try {
            if (digits.length() >= 14) return LocalDateTime.parse(digits.substring(0, 14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            if (digits.length() >= 8) return LocalDate.parse(digits.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay();
        } catch (DateTimeParseException ignored) { }
        return null;
    }
}
