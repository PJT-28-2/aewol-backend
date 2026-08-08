package com.aewol.domain.certificate.service;

import com.aewol.domain.certificate.dto.ApmsSyncRequest;
import com.aewol.domain.certificate.dto.PetRegistrationCandidateResponse;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.external.apms.ApmsClient;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CertificateServiceImpl implements CertificateService {

    private final ApmsClient apmsClient;
    private final PetMapper petMapper;

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
