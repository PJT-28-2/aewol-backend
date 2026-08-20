package com.aewol.domain.pet.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.DateTimeUtil;
import com.aewol.domain.pet.dto.PetRegistrationResponse;
import com.aewol.domain.pet.dto.PetRegistrationVerifyRequest;
import com.aewol.domain.pet.mapper.PetDocumentMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.pet.mapper.PetRegistrationMapper;
import com.aewol.external.apms.ApmsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class PetRegistrationServiceImpl implements PetRegistrationService {
    private static final String REGISTRATION = "REGISTRATION";

    private final PetMapper petMapper;
    private final PetDocumentMapper petDocumentMapper;
    private final PetRegistrationMapper petRegistrationMapper;
    private final ApmsClient apmsClient;

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

        // 방금 저장한 registration(메모리 맵)을 그대로 응답하면 last_synced_at이 빠진다 —
        // 그 컬럼은 insert/update SQL의 NOW()로 DB에서만 채워지고 메모리 맵엔 없기 때문이다.
        // getDetail()과 동일하게 저장된 값을 다시 조회해서 응답한다 — 같은 트랜잭션 안이라
        // 방금 커밋한 값이 그대로 보이고, regTm/aprTm 포맷도 getDetail()과 일치하게 된다.
        Map<String, Object> saved = petRegistrationMapper.findByPetIdAndDocId(
                petId, String.valueOf(registration.get("docId")));
        return toResponse(saved);
    }

    /**
     * 등록증 상세 조회(순서: 증명서 상세). verify()와 달리 APMS를 다시 호출하지 않고
     * pet_registration에 저장된 값을 그대로 돌려준다 — lastSyncedAt이 "마지막 동기화 시각"이다.
     *
     * pet_registration.doc_id는 항상 그 등록증의 pet_document.doc_id와 같은 값이다: verify()가
     * insert/update 직전에 registration.put("docId", value(document, "doc_id", "docId"))로 방금
     * 만들어지거나 조회된 pet_document 행의 doc_id를 그대로 실어 보내고(위 83번째 줄), 두 insert가
     * 같은 @Transactional 안에서 함께 커밋되며, fk_petreg_doc FK가 DB 레벨에서도 이를 보장한다.
     * 따라서 pet_document에 REGISTRATION 타입 행이 있는데 여기서 404가 나는 경우는 정상 흐름상
     * 발생하지 않는다 — 그런 상황이 실제로 발생한다면 이 메서드가 아니라 데이터 정합성 자체를 봐야 한다.
     */
    @Override
    public PetRegistrationResponse getDetail(String memberId, String petId, String docId) {
        Map<String, Object> pet = petMapper.findById(petId);
        if (pet == null) throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        if (!Objects.equals(memberId, String.valueOf(pet.get("member_id")))) {
            throw BusinessException.forbidden("대표 보호자만 이 작업을 할 수 있습니다.");
        }

        Map<String, Object> registration = petRegistrationMapper.findByPetIdAndDocId(petId, docId);
        if (registration == null) {
            throw BusinessException.notFound("동물등록정보를 찾을 수 없습니다.");
        }
        return toResponse(registration);
    }

    @Override
    @Transactional
    public void cancel(String memberId, String petId, String docId) {
        Map<String, Object> pet = petMapper.findById(petId);
        if (pet == null) throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        if (!Objects.equals(memberId, String.valueOf(pet.get("member_id")))) {
            throw BusinessException.forbidden("대표 보호자만 이 작업을 할 수 있습니다.");
        }

        // fk_petreg_doc가 pet_document.doc_id를 참조하므로, pet_document를 지우기 전에
        // 자식 행인 pet_registration을 먼저 지워야 한다.
        petRegistrationMapper.deleteByPetIdAndDocId(petId, docId);
        petMapper.updateRegistrationNumber(petId, memberId, null);
    }

    /** pet_registration 테이블 행(DB 네이티브 컬럼)을 응답으로 매핑한다. verify()/getDetail() 공용. */
    private PetRegistrationResponse toResponse(Map<String, Object> registration) {
        return PetRegistrationResponse.builder()
                .docId(string(registration.get("doc_id")))
                .petId(string(registration.get("pet_id")))
                .regNumber(string(registration.get("reg_number")))
                .name(string(registration.get("name")))
                .breed(string(registration.get("breed")))
                .gender(string(registration.get("gender")))
                .neutered(string(registration.get("neutered")))
                .birthDate(string(registration.get("birth_date")))
                .rfidCd(string(registration.get("rfid_cd")))
                .rfidGubun(string(registration.get("rfid_gubun")))
                .orgNm(string(registration.get("org_nm")))
                .officeTel(string(registration.get("office_tel")))
                .aprGbnNm(string(registration.get("apr_gbn_nm")))
                .regTm(DateTimeUtil.toIsoString(registration.get("reg_tm")))
                .aprTm(DateTimeUtil.toIsoString(registration.get("apr_tm")))
                .lastSyncedAt(DateTimeUtil.toIsoString(registration.get("last_synced_at")))
                .verified(true)
                .build();
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
        String trimmed = value.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.equals("O") || upper.equals("Y")) return "Y";
        if (upper.equals("X") || upper.equals("N")) return "N";
        // APMS는 neuterYn에 "중성"/"미중성" 같은 한글 값을 준다. pet_registration.neutered가
        // CHAR(1)이라 알 수 없는 값을 그대로 저장하면 truncation 에러가 나므로, 인식 못한
        // 값은 저장하지 않고 null로 둔다.
        if (trimmed.contains("중성")) {
            return trimmed.contains("미") ? "N" : "Y";
        }
        if (upper.length() == 1) return upper;
        log.warn("APMS neuterYn 값을 인식하지 못해 저장하지 않습니다 - value: {}", value);
        return null;
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
