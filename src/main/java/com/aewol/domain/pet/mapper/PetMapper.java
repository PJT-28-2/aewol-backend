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
    int updateRegistrationNumber(@Param("petId") String petId,
                                 @Param("memberId") String memberId,
                                 @Param("regNumber") String regNumber);

    /** 동물등록증 연동 성공 시 견종/중성화 여부를 등록증 값으로 동기화한다. APMS가 값을 안 주면(null) 기존 값을 유지한다. */
    int updateRegistrationDetails(@Param("petId") String petId,
                                  @Param("breed") String breed,
                                  @Param("neutered") String neutered);

    /** AI가 생성한 캐릭터 이미지 경로를 저장한다. */
    int updateCharacterImages(@Param("petId") String petId,
                              @Param("memberId") String memberId,
                              @Param("profileImg") String profileImg,
                              @Param("characterImg") String characterImg);
}
