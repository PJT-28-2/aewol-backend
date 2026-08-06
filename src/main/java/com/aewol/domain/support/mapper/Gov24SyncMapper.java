package com.aewol.domain.support.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 정부24 원본 적재 및 공공지원사업 큐레이션 전용 매퍼.
 * 화면 조회는 {@link SupportMapper}가 담당한다.
 */
@Mapper
public interface Gov24SyncMapper {

    int upsertService(Map<String, Object> service);

    int upsertServiceDetail(Map<String, Object> detail);

    int upsertSupportCondition(Map<String, Object> condition);

    /** program_id는 AUTO_INCREMENT — 신규 시 파라미터 맵의 programId로 채워진다. */
    int upsertCuratedProgram(Map<String, Object> program);

    Long findProgramIdBySourceServiceId(@Param("sourceServiceId") String sourceServiceId);

    int deleteGeneratedConditions(@Param("programId") Long programId);

    int insertProgramCondition(Map<String, Object> condition);

    int deactivateAllGov24Programs();
}
