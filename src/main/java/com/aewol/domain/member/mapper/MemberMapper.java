package com.aewol.domain.member.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Map;

@Mapper
public interface MemberMapper {
    Map<String, Object> findById(@Param("memberId") String memberId);
    Map<String, Object> findAuthStateById(@Param("memberId") String memberId);
    Map<String, Object> findByEmail(@Param("email") String email);
    Map<String, Object> findActiveByEmail(@Param("email") String email);
    Map<String, Object> findActiveKakaoByIdentity(
            @Param("email") String email, @Param("providerId") String providerId);
    boolean existsActiveByEmail(@Param("email") String email);
    boolean existsActiveById(@Param("memberId") String memberId);
    boolean existsInactiveByKakaoIdentity(
            @Param("email") String email, @Param("providerId") String providerId);
    Map<String, Object> findLatestInactiveByEmailForUpdate(@Param("email") String email);
    void insert(Map<String, Object> member);
    int restoreLocalMember(Map<String, Object> member);
    int deactivateActiveMember(@Param("memberId") String memberId);
    void update(Map<String, Object> member);
}
