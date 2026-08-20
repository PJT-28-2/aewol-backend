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
     * 동물등록증 검증을 해제한다. pet_registration 행을 삭제하고 pet.reg_number도 비운다.
     * getDetail()과 같은 이유로 memberId 소유권을 이 메서드 자신이 검증한다.
     */
    void cancel(String memberId, String petId, String docId);
}
