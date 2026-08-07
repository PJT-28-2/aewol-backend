package com.aewol.domain.pet.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface PetDocumentMapper {

    void insert(Map<String, Object> document);

    List<Map<String, Object>> findByPetId(@Param("petId") String petId);

    Map<String, Object> findByPetIdAndType(@Param("petId") String petId,
                                           @Param("docType") String docType);

    Map<String, Object> findByIdAndPetId(@Param("docId") String docId,
                                         @Param("petId") String petId);

    void update(Map<String, Object> document);

    int deleteByIdAndPetId(@Param("docId") String docId,
                           @Param("petId") String petId);
}
