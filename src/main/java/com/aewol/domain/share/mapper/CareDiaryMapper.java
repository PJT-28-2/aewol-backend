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
               @Param("content") String content,
               @Param("version") Long version);

    List<Map<String, Object>> findImagesForPublish(@Param("diaryId") String diaryId);

    int updatePublicImageKey(@Param("imageId") String imageId,
                             @Param("publicImageKey") String publicImageKey);

    int updateVisibility(@Param("diaryId") String diaryId,
                         @Param("visibility") String visibility);

    /** @return 1이면 새 신고, 0이면 같은 사람이 이미 신고한 건 (UNIQUE 제약) */
    int insertReport(Map<String, Object> report);

    int linkReportInquiry(@Param("reportId") String reportId,
                          @Param("inquiryId") String inquiryId);

    int hideByReport(@Param("diaryId") String diaryId);

    int countReportsByDiary(@Param("diaryId") String diaryId);

    List<Map<String, Object>> findAdminReports(@Param("status") String status,
                                               @Param("limit") int limit,
                                               @Param("offset") int offset);

    Map<String, Object> findAdminReportById(@Param("reportId") String reportId);

    int resolvePendingReportsByDiary(@Param("diaryId") String diaryId,
                                     @Param("resolution") String resolution,
                                     @Param("adminNote") String adminNote,
                                     @Param("resolvedBy") String resolvedBy);

    int resolvePendingReportsByInquiryId(@Param("inquiryId") String inquiryId,
                                         @Param("resolution") String resolution,
                                         @Param("adminNote") String adminNote,
                                         @Param("resolvedBy") String resolvedBy);

    int restoreByReport(@Param("diaryId") String diaryId);

    int softDelete(@Param("diaryId") String diaryId);
}
