package com.aewol.domain.share.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CareDiaryMapper {

    /** diary_id는 AUTO_INCREMENT — useGeneratedKeys가 파라미터 맵에 채워준다. */
    void insert(Map<String, Object> diary);

    void insertImage(Map<String, Object> image);

    /** 해당 월(yearMonth: yyyy-MM)의 일기를 최신순으로 조회한다. */
    List<Map<String, Object>> findByPetIdAndMonth(@Param("petId") String petId,
                                                  @Param("yearMonth") String yearMonth);

    Map<String, Object> findById(@Param("diaryId") String diaryId);

    List<Map<String, Object>> findImagesByDiaryIds(@Param("diaryIds") List<String> diaryIds);

    int update(@Param("diaryId") String diaryId,
               @Param("diaryDate") String diaryDate,
               @Param("content") String content);

    int softDelete(@Param("diaryId") String diaryId);
}
