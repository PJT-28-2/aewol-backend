package com.aewol.domain.share.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ShareMapper {
    List<Map<String, Object>> findAccessiblePets(@Param("memberId") String memberId);
    Map<String, Object> findMemberByPhone(@Param("phone") String phone);
    Map<String, Object> findActiveInvite(@Param("petId") String petId,
                                         @Param("recipientValue") String recipientValue,
                                         @Param("memberId") String memberId);
    Map<String, Object> findByAccessId(@Param("accessId") String accessId);
    Map<String, Object> findByInviteCode(@Param("inviteCode") String inviteCode);
    Map<String, Object> findAcceptedAccess(@Param("petId") String petId,
                                           @Param("memberId") String memberId);
    Map<String, Object> findMainWalletByMemberId(@Param("memberId") String memberId);
    void insert(Map<String, Object> sharedAccess);
    int acceptInvite(@Param("accessId") String accessId, @Param("memberId") String memberId);
    int updateStatus(@Param("accessId") String accessId,
                     @Param("memberId") String memberId,
                     @Param("status") String status);
    int markExpired(@Param("accessId") String accessId);
    List<Map<String, Object>> findMembersByPetId(@Param("petId") String petId);
    List<Map<String, Object>> findMonthlyContributions(@Param("petId") String petId);
    List<Map<String, Object>> findLogsByPetId(@Param("petId") String petId);
    int updateRole(@Param("petId") String petId,
                   @Param("memberId") String memberId,
                   @Param("role") String role);
    int revoke(@Param("petId") String petId, @Param("memberId") String memberId);
}
