package com.aewol.domain.pet.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PetRegistrationResponse {
    private String docId;
    private String petId;
    private String regNumber;
    private String name;
    private String breed;
    private String gender;
    private String neutered;
    private String birthDate;
    private String rfidCd;
    private String rfidGubun;
    private String orgNm;
    private String officeTel;
    private String aprGbnNm;
    private String regTm;
    private String aprTm;
    /** 마지막으로 APMS에서 다시 받아온 시각. 재동기화 버튼 옆에 표시한다. */
    private String lastSyncedAt;
    private boolean verified;
}
