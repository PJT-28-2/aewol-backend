package com.aewol.domain.grouppurchase.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface GroupPurchaseMapper {
    List<Map<String, Object>> findList(@Param("status") String status, @Param("keyword") String keyword,
                                        @Param("category") String category, @Param("limit") int limit,
                                        @Param("offset") int offset);
    Map<String, Object> findById(@Param("gpId") String gpId);
    void insert(Map<String, Object> groupPurchase);
    int updateQuantity(@Param("gpId") String gpId, @Param("quantity") int quantity);
    void insertParticipant(Map<String, Object> participant);
    Map<String, Object> findParticipant(@Param("gpId") String gpId, @Param("memberId") String memberId);
}
