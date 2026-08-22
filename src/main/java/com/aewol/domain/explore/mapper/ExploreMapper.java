package com.aewol.domain.explore.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 공개 일기 전용 조회.
 *
 * <p>기존 {@code CareDiaryMapper}에 플래그를 얹지 않고 매퍼를 따로 둔다. 공개 조회는
 * {@code assertCanAccess}를 거치지 않는 경로라, 실수 한 번이면 비공개 일기가 전부 샌다.
 * 여기 있는 SQL은 전부 공개 조건을 직접 박아 두고 그 조건 없이 도는 쿼리를 만들지 않는다.
 */
@Mapper
public interface ExploreMapper {

    List<Map<String, Object>> findPublicPosts(@Param("cursorCreatedAt") String cursorCreatedAt,
                                              @Param("cursorDiaryId") String cursorDiaryId,
                                              @Param("limit") int limit);

    List<Map<String, Object>> findPublicPostsByPet(@Param("petId") String petId,
                                                   @Param("cursorCreatedAt") String cursorCreatedAt,
                                                   @Param("cursorDiaryId") String cursorDiaryId,
                                                   @Param("limit") int limit);

    Map<String, Object> findPublicPost(@Param("diaryId") String diaryId);

    Map<String, Object> findPublicProfile(@Param("petId") String petId);

    List<Map<String, Object>> findImagesByDiaryIds(@Param("diaryIds") List<String> diaryIds);
}
