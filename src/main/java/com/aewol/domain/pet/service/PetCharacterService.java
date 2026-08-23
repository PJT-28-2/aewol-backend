package com.aewol.domain.pet.service;

import com.aewol.domain.pet.dto.PetCharacterResponse;
import com.aewol.domain.pet.job.PetCharacterJob;
import org.springframework.web.multipart.MultipartFile;

public interface PetCharacterService {

    /**
     * 캐릭터를 만들어 완성될 때까지 기다린다.
     *
     * @deprecated 20~25초 동안 요청 스레드를 붙잡고, 앞단 프록시의 읽기 타임아웃에 걸릴 수
     *         있다. {@link #submit}을 쓴다. 프론트가 옮겨간 뒤에 지운다(#346).
     */
    @Deprecated
    PetCharacterResponse generate(String memberId, String petId, MultipartFile photo);

    /**
     * 캐릭터 생성을 접수하고 곧바로 돌아온다.
     *
     * <p>검증과 할당량 차감은 여기서 끝낸다. 잘못된 요청이면 작업을 만들지 않고 바로 실패를
     * 알린다 — 접수해 놓고 나중에 "사실 안 되는 요청이었다"고 답하면 사용자는 그동안
     * 기다린 셈이 된다.
     *
     * @return 접수된 작업. 상태는 항상 RUNNING이다.
     */
    PetCharacterJob submit(String memberId, String petId, MultipartFile photo);

    /**
     * 접수한 작업의 상태를 돌려준다.
     *
     * @return 없거나 남의 작업이면 {@code null}
     */
    PetCharacterJob findJob(String memberId, String jobId);
}
