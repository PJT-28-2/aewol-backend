package com.aewol.domain.pet.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface PetDocumentMapper {

    void insert(Map<String, Object> document);

    List<Map<String, Object>> findByPetId(@Param("petId") String petId);

    Map<String, Object> findByPetIdAndTypeForUpdate(@Param("petId") String petId,
                                                    @Param("docType") String docType);

    Map<String, Object> findByIdAndPetIdForUpdate(@Param("docId") String docId,
                                                  @Param("petId") String petId);

    /** 조회 전용. 갱신이 없는 흐름에서 행을 잠그지 않기 위해 ForUpdate와 나눠 둔다. */
    Map<String, Object> findByIdAndPetId(@Param("docId") String docId,
                                         @Param("petId") String petId);

    void update(Map<String, Object> document);

    int deleteByIdAndPetId(@Param("docId") String docId,
                           @Param("petId") String petId);
}
