package com.aewol.domain.pet.service;

import com.aewol.domain.pet.dto.PetRegistrationResponse;
import com.aewol.domain.pet.dto.PetRegistrationVerifyRequest;

public interface PetRegistrationService {
    PetRegistrationResponse verify(String memberId, String petId, PetRegistrationVerifyRequest request);

    /**
     * memberId가 petId의 대표 보호자인지 이 메서드 자신이 검증한다 — 호출부(PetServiceImpl)가
     * 미리 검증했다는 전제에 기대지 않는다. 이 서비스를 다른 곳에서 직접 호출하더라도
     * 소유권 없이 등록증 상세(RFID 등 개인 정보 포함)가 노출되지 않도록 하기 위해서다.
     */
    PetRegistrationResponse getDetail(String memberId, String petId, String docId);

    /**
     * 이미 검증해 저장한 등록번호로 APMS 정보를 다시 조회한다.
     * 소유자 이름·생년월일을 다시 받지 않으며, 저장된 등록번호 외의 값은 조회 조건으로 사용하지 않는다.
     */
    PetRegistrationResponse resync(String memberId, String petId, String docId);
}
