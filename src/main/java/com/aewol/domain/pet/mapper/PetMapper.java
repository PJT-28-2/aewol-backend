package com.aewol.domain.pet.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface PetMapper {
    Map<String, Object> findById(@Param("petId") String petId);
    Map<String, Object> findByIdAndMemberId(@Param("petId") String petId, @Param("memberId") String memberId);
    List<Map<String, Object>> findByMemberId(@Param("memberId") String memberId);
    void insert(Map<String, Object> pet);
    void update(Map<String, Object> pet);
    int deactivate(@Param("petId") String petId, @Param("memberId") String memberId);
}
