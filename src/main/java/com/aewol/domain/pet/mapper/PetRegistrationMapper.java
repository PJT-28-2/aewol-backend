package com.aewol.domain.pet.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

@Mapper
public interface PetRegistrationMapper {
    Map<String, Object> findByRegNumber(@Param("regNumber") String regNumber);
    /** 상세 조회·재동기화용. 등록증 한 건의 전체 항목을 돌려준다. */
    Map<String, Object> findByDocIdAndPetId(@Param("docId") String docId,
                                            @Param("petId") String petId);

    void insert(Map<String, Object> registration);
    int update(Map<String, Object> registration);
}
