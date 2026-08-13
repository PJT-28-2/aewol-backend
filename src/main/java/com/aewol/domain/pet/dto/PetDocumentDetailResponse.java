package com.aewol.domain.pet.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 반려동물 문서 상세.
 *
 * <p>등록증(REGISTRATION)이면 {@code registration} 에 동물등록정보가 함께 담긴다.
 * 접종증명서·진료확인서처럼 외부 조회가 없는 문서는 그 자리가 비어 있다.
 */
@Getter
@Builder
public class PetDocumentDetailResponse {

    private final String docId;
    private final String petId;
    private final String docName;
    private final String docType;
    /** 업로드 파일이 있으면 만료되는 서명 URL. 없으면 null. */
    private final String fileUrl;
    private final String issuedDate;
    private final String createdAt;
    private final PetRegistrationResponse registration;
}
