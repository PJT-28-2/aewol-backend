package com.aewol.domain.explore.dto;

import lombok.Builder;
import lombok.Getter;

/** 반려동물 계정 프로필. 사람 정보는 포함하지 않는다. */
@Getter
@Builder
public class PetPublicProfileResponse {

    private final String petId;
    private final String name;
    private final String species;
    private final String breed;
    /** AI 캐릭터가 있으면 그것을, 없으면 실사진을 쓴다. 둘 다 없으면 화면이 종별 기본 캐릭터로 채운다. */
    private final String profileImage;
    /** 반려동물 인스타그램 핸들(@ 제외). 없으면 null. */
    private final String instagramId;
    private final int postCount;
}
