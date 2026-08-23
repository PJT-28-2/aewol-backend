package com.aewol.domain.pet.dto;

import com.aewol.domain.pet.job.PetCharacterJob;
import lombok.Builder;
import lombok.Getter;

/** 캐릭터 생성 작업의 접수 결과와 진행 상태. */
@Getter
@Builder
public class PetCharacterJobResponse {

    private final String jobId;
    private final String petId;
    /** RUNNING / DONE / FAILED */
    private final String status;

    /** DONE일 때만 채워진다. */
    private final String profileImg;
    private final String characterImg;
    private final Integer remainingToday;

    /** FAILED일 때 사용자에게 보여줄 이유. */
    private final String message;

    public static PetCharacterJobResponse from(PetCharacterJob job) {
        return PetCharacterJobResponse.builder()
                .jobId(job.getJobId())
                .petId(job.getPetId())
                .status(job.getStatus().name())
                .profileImg(job.getProfileImg())
                .characterImg(job.getCharacterImg())
                .remainingToday(job.getRemainingToday())
                .message(job.getMessage())
                .build();
    }
}
