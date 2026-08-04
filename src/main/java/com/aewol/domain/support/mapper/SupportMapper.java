package com.aewol.domain.support.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SupportMapper {
    List<Map<String, Object>> findActivePrograms();
    Map<String, Object> findProgramById(@Param("programId") String programId);
    List<Map<String, Object>> findConditions(@Param("programId") String programId);
    Map<String, Object> findInterest(@Param("memberId") String memberId,
                                     @Param("programId") String programId);
    void upsertInterest(Map<String, Object> interest);
}
