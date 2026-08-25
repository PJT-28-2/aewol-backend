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
     * PRIVATE/PUBLIC(부분 공개) → PUBLISHING 정상 진입. 진행 중인 작업이 전혀 없을 때만
     * (publish_token IS NULL) 통한다. 이 경로로 들어왔다는 것은 예전 시도가 없었거나 이미
     * 완전히 끝나고 토큰을 반납했다는 뜻이므로, 이미지에 남은 기존 공개 키를 그대로 믿어도
     * 된다 — {@link #reclaimStalePublishing}과 반드시 구분해서 써야 하는 이유다.
     */
    int enterPublishingFresh(@Param("diaryId") String diaryId, @Param("token") String token);

    /**
     * 죽은 작업을 회수한다. {@code publishing_started_at}이 {@code staleBefore}보다 오래됐을
     * 때만(=완료도 취소도 못 하고 멈춘 것으로 본다) 새 토큰으로 가져온다.
     *
     * <p>이 경로로 들어왔다면 이미지에 남은 공개 키는 신뢰할 수 없다 — 이전 시도가 DB에
     * 키만 예약해두고 S3 복사 전에 죽었을 수도 있고(P1: 사본 없이 PUBLIC 확정), 사실은
     * 아직 살아서 그 키로 복사 중일 수도 있다(P1: 완료 후 살아있는 작업이 같은 키를
     * 지워버림). 호출부는 이 경로를 탔으면 기존 키를 버리고 이미지마다 새 키를 발급해
     * 사본을 전부 다시 만들어야 한다 — 그래야 이전 시도가 무엇을 하고 있었든 서로 다른
     * 키를 쓰게 되어 나중에 어느 쪽이 끝나도 상대방의 결과를 건드리지 않는다.
     */
    int reclaimStalePublishing(@Param("diaryId") String diaryId,
                               @Param("token") String token,
                               @Param("staleBefore") LocalDateTime staleBefore);

    /** visibility·publish_token을 건드리지 않고 이미지 예약 작업만 위한 토큰을 건다(관리자 복원용). */
    int acquirePublishTokenFresh(@Param("diaryId") String diaryId, @Param("token") String token);

    /** 관리자 복원 작업용 회수. 신뢰할 수 없는 기존 키는 {@link #reclaimStalePublishing}과 같은 이유로 호출부가 버려야 한다. */
    int reclaimStalePublishToken(@Param("diaryId") String diaryId,
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
