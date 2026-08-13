package com.aewol.domain.certificate.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.certificate.dto.ApmsSyncRequest;
import com.aewol.domain.certificate.dto.PetRegistrationCandidateResponse;
import com.aewol.domain.certificate.dto.RegistrationConfirmRequest;
import com.aewol.domain.pet.dto.PetRegistrationResponse;
import com.aewol.domain.pet.mapper.PetDocumentMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.pet.mapper.PetRegistrationMapper;
import com.aewol.external.apms.ApmsClient;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CertificateServiceImpl implements CertificateService {

    private static final String REGISTRATION = "REGISTRATION";

    private final ApmsClient apmsClient;
    private final PetMapper petMapper;
    private final PetDocumentMapper petDocumentMapper;
    private final PetRegistrationMapper petRegistrationMapper;

    @Override
    public List<PetRegistrationCandidateResponse> syncRegistration(String memberId, ApmsSyncRequest request) {
        Optional<Map<String, Object>> item = apmsClient.findRegistration(
                request.getRegNumber(), request.getUserName());

        if (item.isEmpty()) {
            return Collections.emptyList();
        }

        String matchedPetId = findMatchingPetId(memberId, request.getRegNumber());
        return List.of(toCandidate(item.get(), matchedPetId));
    }

    @Override
    @Transactional
    public List<PetRegistrationResponse> confirmRegistration(String memberId, RegistrationConfirmRequest request) {
        List<PetRegistrationResponse> saved = new ArrayList<>();
        for (RegistrationConfirmRequest.Candidate candidate : request.getCandidates()) {
            saved.add(link(memberId, candidate));
        }
        return saved;
    }

    /**
     * 후보 한 건을 반려동물에 연동한다.
     *
     * <p>후보 값은 우리 sync 응답에서 나온 것이지만 클라이언트를 거쳐 돌아오므로
     * 그대로 믿지 않는다. 반려동물 소유권과 등록번호 중복은 서버에서 다시 확인한다.
     * 다만 등록증 항목 자체(품종·성별 등)는 후보에 담겨 온 값을 쓴다 — 사용자가
     * 방금 sync로 조회해 고른 값이고, 여기서 APMS를 다시 부르면 소유자 이름을
     * 한 번 더 받아야 해 화면 흐름이 끊긴다.
     */
    private PetRegistrationResponse link(String memberId, RegistrationConfirmRequest.Candidate candidate) {
        String petId = candidate.getPetId();
        Map<String, Object> pet = petMapper.findById(petId);
        if (pet == null) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
        if (!Objects.equals(memberId, String.valueOf(pet.get("member_id")))) {
            throw BusinessException.forbidden("대표 보호자만 이 작업을 할 수 있습니다.");
        }

        String regNumber = candidate.getRegNumber();
        Map<String, Object> owner = petRegistrationMapper.findByRegNumber(regNumber);
        if (owner != null && !owner.isEmpty()
                && !Objects.equals(petId, String.valueOf(owner.get("pet_id")))) {
            throw BusinessException.conflict("이미 다른 반려동물에 사용 중인 동물등록번호입니다.");
        }

        Map<String, Object> document = petDocumentMapper.findByPetIdAndTypeForUpdate(petId, REGISTRATION);
        boolean first = document == null;
        String docName = (candidate.getName() == null ? "" : candidate.getName()) + " 동물등록증";
        if (first) {
            document = new HashMap<>();
            document.put("petId", petId);
            document.put("docName", docName);
            document.put("docType", REGISTRATION);
            document.put("fileUrl", null);
            document.put("issuedDate", null);
            petDocumentMapper.insert(document);
        } else {
            document.put("petId", petId);
            document.put("docName", docName);
            document.put("fileUrl", document.get("file_url"));
            document.put("issuedDate", document.get("issued_date"));
            document.put("docId", docId(document));
            petDocumentMapper.update(document);
        }

        Map<String, Object> registration = toRegistrationRow(candidate, petId, docId(document));
        try {
            if (first) {
                petRegistrationMapper.insert(registration);
            } else if (petRegistrationMapper.update(registration) != 1) {
                throw BusinessException.notFound("동물등록정보를 찾을 수 없습니다.");
            }
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("이미 다른 반려동물에 사용 중인 동물등록번호입니다.");
        }

        if (petMapper.updateRegistrationNumber(petId, memberId, regNumber) != 1) {
            throw BusinessException.notFound("반려동물을 찾을 수 없습니다.");
        }
        return response(registration);
    }

    private static Object docId(Map<String, Object> document) {
        return document.containsKey("doc_id") ? document.get("doc_id") : document.get("docId");
    }

    private Map<String, Object> toRegistrationRow(RegistrationConfirmRequest.Candidate candidate,
                                                  String petId, Object docId) {
        Map<String, Object> row = new HashMap<>();
        row.put("docId", docId);
        row.put("petId", petId);
        row.put("regNumber", candidate.getRegNumber());
        row.put("name", candidate.getName());
        row.put("breed", candidate.getBreed());
        row.put("gender", candidate.getGender());
        row.put("neutered", candidate.getNeutered());
        row.put("birthDate", candidate.getBirthDate());
        row.put("rfidCd", candidate.getRfidCd());
        row.put("rfidGubun", candidate.getRfidGubun());
        row.put("orgNm", candidate.getOrgNm());
        row.put("officeTel", candidate.getOfficeTel());
        row.put("aprGbnNm", candidate.getAprGbnNm());
        row.put("regTm", parseDateTime(candidate.getRegTm()));
        row.put("aprTm", parseDateTime(candidate.getAprTm()));
        return row;
    }

    private PetRegistrationResponse response(Map<String, Object> row) {
        return PetRegistrationResponse.builder()
                .docId(str(row.get("docId"))).petId(str(row.get("petId")))
                .regNumber(str(row.get("regNumber"))).name(str(row.get("name")))
                .breed(str(row.get("breed"))).gender(str(row.get("gender")))
                .neutered(str(row.get("neutered"))).birthDate(str(row.get("birthDate")))
                .rfidCd(str(row.get("rfidCd"))).rfidGubun(str(row.get("rfidGubun")))
                .orgNm(str(row.get("orgNm"))).officeTel(str(row.get("officeTel")))
                .aprGbnNm(str(row.get("aprGbnNm"))).regTm(str(row.get("regTm")))
                .aprTm(str(row.get("aprTm"))).verified(true).build();
    }

    /** APMS 날짜는 yyyyMMdd 또는 yyyyMMddHHmmss로 온다. 형식이 다르면 비운다. */
    private static LocalDateTime parseDateTime(String value) {
        if (value == null) return null;
        String digits = value.replaceAll("\\D", "");
        try {
            if (digits.length() >= 14) {
                return LocalDateTime.parse(digits.substring(0, 14),
                        DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            }
            if (digits.length() >= 8) {
                return LocalDate.parse(digits.substring(0, 8),
                        DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay();
            }
        } catch (DateTimeParseException ignored) {
            // 형식이 어긋나면 값 없이 저장한다. 화면에서 비어 보이는 편이
            // 저장 자체가 실패하는 것보다 낫다.
        }
        return null;
    }

    /**
     * 등록번호가 회원 소유 반려동물 중 하나와 이미 일치하면 그 petId를 채워서 내려준다.
     * 일치하는 반려동물이 없으면 null(신규 매칭 대상) — 어떤 화면 흐름으로 petId를
     * 확정할지는 아직 정해지지 않아 후속 논의가 필요하다.
     */
    private String findMatchingPetId(String memberId, String regNumber) {
        return petMapper.findByMemberId(memberId).stream()
                .filter(pet -> Objects.equals(regNumber, pet.get("reg_number")))
                .map(pet -> String.valueOf(pet.get("pet_id")))
                .findFirst()
                .orElse(null);
    }

    private PetRegistrationCandidateResponse toCandidate(Map<String, Object> item, String petId) {
        return PetRegistrationCandidateResponse.builder()
                .petId(petId)
                .regNumber(str(item.get("dogRegNo")))
                .name(str(item.get("dogNm")))
                .breed(str(item.get("kindNm")))
                .gender(str(item.get("sexNm")))
                .neutered(str(item.get("neuterYn")))
                .birthDate(str(item.get("birthDt")))
                .rfidCd(str(item.get("rfidCd")))
                .rfidGubun(str(item.get("rfidGubun")))
                .orgNm(str(item.get("orgNm")))
                // officeTel은 animalInfoSrvc_v3 응답에서 확인되지 않는 필드라 항상 null로 내려간다.
                .officeTel(null)
                .aprGbnNm(str(item.get("aprGbNm")))
                .regTm(str(item.get("regTm")))
                .aprTm(str(item.get("aprTm")))
                .build();
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
