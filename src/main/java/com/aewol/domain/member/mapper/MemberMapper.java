package com.aewol.domain.member.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface MemberMapper {
    Map<String, Object> findById(@Param("memberId") String memberId);
    Map<String, Object> findAuthStateById(@Param("memberId") String memberId);
    Map<String, Object> findByEmail(@Param("email") String email);
    Map<String, Object> findActiveByEmail(@Param("email") String email);
    Map<String, Object> findActiveKakaoByProviderId(@Param("providerId") String providerId);
    boolean existsActiveByEmail(@Param("email") String email);
    boolean existsActiveById(@Param("memberId") String memberId);
    boolean existsActiveByPhone(@Param("phone") String phone);
    boolean existsActiveByPhoneExcludingMember(
            @Param("phone") String phone, @Param("memberId") String memberId);
    List<Map<String, Object>> findActiveForAccountFind(
            @Param("name") String name, @Param("phone") String phone);
    Map<String, Object> findActiveAccountFindResultById(@Param("memberId") String memberId);
    boolean existsInactiveKakaoByProviderId(@Param("providerId") String providerId);
    Map<String, Object> findInactiveKakaoByProviderIdForUpdate(
            @Param("providerId") String providerId);
    Map<String, Object> findLatestInactiveByEmailForUpdate(@Param("email") String email);
    List<Long> findRetentionCleanupCandidateIds();
    Map<String, Object> findRetentionCleanupTargetForUpdate(@Param("memberId") Long memberId);
    void insert(Map<String, Object> member);
    int restoreLocalMember(Map<String, Object> member);
    int restoreKakaoMember(@Param("memberId") Long memberId);
    int deactivateActiveMember(@Param("memberId") String memberId);
    void updateProfile(Map<String, Object> member);
    int updatePassword(@Param("memberId") String memberId, @Param("password") String password);
    int updateSimplePassword(@Param("memberId") String memberId, @Param("simplePassword") String simplePassword);
    int anonymizeLinkedAccounts(@Param("memberId") Long memberId);
    int deleteAccountVerifications(@Param("memberId") Long memberId);
    int deleteNotifications(@Param("memberId") Long memberId);
    int deleteNotificationSetting(@Param("memberId") Long memberId);
    int deleteDonationSetting(@Param("memberId") Long memberId);
    int deleteDonationPreferences(@Param("memberId") Long memberId);
    int deleteSupportProgramInterests(@Param("memberId") Long memberId);
    int deleteHomeInsights(@Param("memberId") Long memberId);
    int purgeMemberIdentity(@Param("memberId") Long memberId);
}
