package com.aewol.domain.pet.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PetCharacterResponse {
    private final String petId;
    /** 정면 얼굴 — 프로필·목록용 */
    private final String profileImg;
    /** 전신 3D 마스코트 — 홈 화면용 */
    private final String characterImg;
    /** 오늘 남은 생성 횟수 */
    private final int remainingToday;
}
