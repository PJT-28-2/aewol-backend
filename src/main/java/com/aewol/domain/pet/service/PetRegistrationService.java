package com.aewol.domain.pet.service;

import com.aewol.domain.pet.dto.PetDocumentDetailResponse;
import com.aewol.domain.pet.dto.PetRegistrationResponse;
import com.aewol.domain.pet.dto.PetRegistrationVerifyRequest;

public interface PetRegistrationService {

    PetRegistrationResponse verify(String memberId, String petId, PetRegistrationVerifyRequest request);

    /** 문서 상세. 등록증이면 동물등록정보를 함께 담아 돌려준다. */
    PetDocumentDetailResponse getDocument(String memberId, String petId, String docId);

    /**
     * 저장된 등록번호로 APMS를 다시 조회해 갱신한다.
     *
     * <p>{@link #verify}와 달리 이름·생년월일을 다시 받지 않는다. 이미 한 번 인증해
     * 저장해 둔 등록번호를 쓰기 때문이다.
     */
    PetRegistrationResponse resync(String memberId, String petId, String docId);
}
