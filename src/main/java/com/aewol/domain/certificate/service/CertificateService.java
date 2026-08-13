package com.aewol.domain.certificate.service;

import com.aewol.domain.certificate.dto.ApmsSyncRequest;
import com.aewol.domain.certificate.dto.PetRegistrationCandidateResponse;
import com.aewol.domain.certificate.dto.RegistrationConfirmRequest;
import com.aewol.domain.pet.dto.PetRegistrationResponse;
import java.util.List;

public interface CertificateService {

    List<PetRegistrationCandidateResponse> syncRegistration(String memberId, ApmsSyncRequest request);

    /**
     * 매칭 화면에서 사용자가 고른 후보를 반려동물에 연동한다.
     *
     * <p>후보마다 반려동물을 확인하고 등록증 문서와 동물등록정보를 저장한다.
     * 이미 연동된 반려동물이면 덮어쓴다.
     */
    List<PetRegistrationResponse> confirmRegistration(String memberId, RegistrationConfirmRequest request);
}
