package com.aewol.domain.pet.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

@Mapper
public interface PetRegistrationMapper {
    Map<String, Object> findByRegNumber(@Param("regNumber") String regNumber);
    Map<String, Object> findByPetIdAndDocId(@Param("petId") String petId, @Param("docId") String docId);
    void insert(Map<String, Object> registration);
    int update(Map<String, Object> registration);
    int deleteByPetIdAndDocId(@Param("petId") String petId, @Param("docId") String docId);
}
