package com.aewol.domain.pet.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PetResponse {
    private String petId;
    private String memberId;
    private String name;
    private String species;
    private String breed;
    private String birthDate;
    private String gender;
    private Double weight;
    private String neutered;
    private String regNumber;
    private String iconType;
    private String medicalHistory;
    /** 얼굴 클로즈업. 프로필 아바타용 */
    private String profileImg;
    /** 전신 3D 캐릭터. 홈 화면 히어로용 */
    private String characterImg;
    private Boolean isActive;
}
