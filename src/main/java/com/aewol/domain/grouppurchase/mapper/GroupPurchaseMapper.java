package com.aewol.domain.grouppurchase.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface GroupPurchaseMapper {
    List<Map<String, Object>> findAll();
    Map<String, Object> findById(@Param("gpId") String gpId);
    void insert(Map<String, Object> groupPurchase);
    void updateQuantity(@Param("gpId") String gpId, @Param("quantity") int quantity);
    void insertParticipant(Map<String, Object> participant);
}
