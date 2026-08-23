package com.aewol.domain.pet.job;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Getter;

/**
 * 캐릭터 생성 작업 하나의 상태.
 *
 * <p>요청이 즉시 끊기므로 결과를 나중에 물어볼 자리가 필요하다.
 */
@Getter
@Builder
// Lombok 빌더는 Jackson이 그냥 읽지 못한다. 어느 빌더를 쓸지, 세터 접두사가 없다는 것을
// 알려줘야 한다.
@JsonDeserialize(builder = PetCharacterJob.PetCharacterJobBuilder.class)
public class PetCharacterJob {

    @JsonPOJOBuilder(withPrefix = "")
    public static class PetCharacterJobBuilder {
    }

    public enum Status {
        /** 아직 만들고 있다. */
        RUNNING,
        /** 다 만들었다. 이미지 주소가 채워져 있다. */
        DONE,
        /** 실패했다. message에 사용자에게 보여줄 이유가 있다. */
        FAILED
    }

    private final String jobId;
    /** 이 작업을 요청한 회원. 남의 작업을 들여다보지 못하게 조회 때 대조한다. */
    private final String memberId;
    private final String petId;
    private final Status status;

    private final String profileImg;
    private final String characterImg;
    private final Integer remainingToday;
    private final String message;
}
