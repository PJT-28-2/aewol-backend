package com.aewol.domain.grouppurchase.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface GroupPurchaseMapper {
    /**
     * cursorIsUrgentActive/cursorDeadline/cursorCreatedAt/cursorGpId 네 필드는 항상 함께
     * null이거나 함께 값이 있어야 한다(GroupPurchaseMapper.xml의 keyset 필터는 cursorGpId
     * != null 하나만 게이팅 조건으로 보고 나머지 셋을 무조건 같이 참조한다). 일부만 채워서
     * 넘기면 예외 없이 조용히 빈 결과가 나온다 — NULL 비교는 항상 false이기 때문이다.
     * GroupPurchaseServiceImpl#list()가 GroupPurchaseCursor를 통해 이 계약을 지킨다.
     *
     * legacyOffset은 커서 4개 필드와 상호 배타적이다 — cursor가 있으면 keyset을, 없고
     * legacyOffset만 있으면 OFFSET을, 둘 다 없으면 첫 페이지(LIMIT만)를 쓴다. 배포 전환
     * 기간 동안 아직 cursor를 안 보내는 구 프론트(page만 전송)를 지원하기 위한 것으로,
     * GroupPurchaseServiceImpl#list(..., legacyPage, ...)를 통해서만 채워진다.
     */
    List<Map<String, Object>> findList(@Param("status") String status, @Param("keyword") String keyword,
                                        @Param("category") String category, @Param("limit") int limit,
                                        @Param("cursorIsUrgentActive") Integer cursorIsUrgentActive,
                                        @Param("cursorDeadline") LocalDateTime cursorDeadline,
                                        @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
                                        @Param("cursorGpId") Long cursorGpId,
                                        @Param("legacyOffset") Integer legacyOffset, @Param("sort") String sort);
    Map<String, Object> findById(@Param("gpId") String gpId);
    void insert(Map<String, Object> groupPurchase);
    int updateQuantity(@Param("gpId") String gpId, @Param("quantity") int quantity);
    void updateDeliveryDate(@Param("gpId") String gpId, @Param("deliveryDate") LocalDate deliveryDate);
    int decreaseQuantity(@Param("gpId") String gpId, @Param("quantity") int quantity);
    int decreaseQuantityForExpired(@Param("gpId") String gpId, @Param("quantity") int quantity);
    void insertParticipant(Map<String, Object> participant);
    Map<String, Object> findParticipant(@Param("gpId") String gpId, @Param("memberId") String memberId);
    List<Long> findParticipatingGpIds(@Param("memberId") String memberId, @Param("gpIds") List<String> gpIds);
    int cancelParticipant(@Param("gpId") String gpId, @Param("memberId") String memberId, @Param("canceledAt") LocalDateTime canceledAt);
    List<Map<String, Object>> findMyGroupPurchases(@Param("memberId") String memberId, @Param("status") String status);
    List<Map<String, Object>> findExpiredUnfulfilledPaidParticipants();
    int cancelGroupPurchase(@Param("gpId") String gpId);
    List<Map<String, Object>> findActiveParticipants(@Param("gpId") String gpId);
    int deactivateExpiredUrgentFlags();
}
