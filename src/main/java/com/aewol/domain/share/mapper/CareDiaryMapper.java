package com.aewol.domain.share.mapper;

import java.time.LocalDateTime;
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

    /**
     * PRIVATE/PUBLIC(부분 공개) → PUBLISHING 전이를 건다. publish_token이 비어 있거나
     * {@code staleBefore}보다 오래전에 발급됐으면(=이전 작업이 죽어서 완료도 취소도
     * 못 한 것으로 본다) 새 토큰으로 가져온다. 그 외(다른 요청이 살아서 진행 중)에는
     * 0행을 반환한다.
     */
    int enterPublishing(@Param("diaryId") String diaryId,
                        @Param("token") String token,
                        @Param("staleBefore") LocalDateTime staleBefore);

    /** visibility·publish_token을 건드리지 않고 이미지 예약 작업만 위한 토큰을 건다(관리자 복원용). */
    int acquirePublishToken(@Param("diaryId") String diaryId,
                            @Param("token") String token,
                            @Param("staleBefore") LocalDateTime staleBefore);

    /** PUBLISHING → PUBLIC. 넘긴 토큰이 지금 그 일기를 쥐고 있을 때만 반영된다. */
    int completePublishing(@Param("diaryId") String diaryId, @Param("token") String token);

    /** PUBLISHING → PRIVATE로 되돌리며 토큰을 비운다. 토큰이 다르면(가로채인 경우) 아무 것도 하지 않는다. */
    int cancelPublishing(@Param("diaryId") String diaryId, @Param("token") String token);

    /** visibility는 그대로 두고 토큰만 비운다(관리자 복원 작업 종료용). */
    int releasePublishToken(@Param("diaryId") String diaryId, @Param("token") String token);

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

    Map<String, Object> findLinkedReportDiaryByInquiryId(@Param("inquiryId") String inquiryId);

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
