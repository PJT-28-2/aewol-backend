package com.aewol.domain.support.service;

/**
 * 정부24 공공서비스 원본을 적재하고 반려동물 관련 사업만 큐레이션한다.
 */
public interface Gov24SyncService {

    /**
     * 반려동물 키워드로 정부24 서비스를 조회해 원본 3개 테이블에 적재하고,
     * local_support_program으로 큐레이션한다.
     *
     * @return 큐레이션된 사업 수
     */
    int syncPetSupportPrograms();
}
