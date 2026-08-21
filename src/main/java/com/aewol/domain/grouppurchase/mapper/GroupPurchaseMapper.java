package com.aewol.domain.grouppurchase.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface GroupPurchaseMapper {
    List<Map<String, Object>> findList(@Param("status") String status, @Param("keyword") String keyword,
                                        @Param("category") String category, @Param("limit") int limit,
                                        @Param("offset") int offset, @Param("sort") String sort);
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
}
