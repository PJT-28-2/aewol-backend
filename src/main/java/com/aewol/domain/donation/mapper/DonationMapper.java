package com.aewol.domain.donation.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 짜투리 저금통(기부함)은 별도 테이블이 아니라 wallet(wallet_type='DONATION') 행이다.
 * 메서드명의 pot은 도메인 용어로 유지하되 식별자는 wallet_id를 사용한다.
 */
@Mapper
public interface DonationMapper {
    Map<String, Object> findPotByMemberId(@Param("memberId") String memberId);
    Map<String, Object> findPotForUpdate(@Param("memberId") String memberId);
    void insertPot(Map<String, Object> pot);
    int decreasePotBalance(@Param("walletId") String walletId, @Param("amount") BigDecimal amount);
    int increasePotBalance(@Param("walletId") String walletId, @Param("amount") BigDecimal amount);
    Map<String, Object> findMainWalletByMemberId(@Param("memberId") String memberId);
    int increaseWalletBalance(@Param("walletId") String walletId, @Param("amount") BigDecimal amount);
    BigDecimal findMonthlySaved(@Param("walletId") String walletId);
    List<Map<String, Object>> findActiveCampaigns(@Param("memberId") String memberId);
    Map<String, Object> findCampaignById(@Param("campaignId") String campaignId);
    Map<String, Object> findActiveOrganizationById(@Param("organizationId") String organizationId);
    int insertPreference(@Param("memberId") String memberId,
                         @Param("organizationId") String organizationId);
    int deletePreference(@Param("memberId") String memberId,
                         @Param("organizationId") String organizationId);
    Map<String, Object> findSettings(@Param("memberId") String memberId);
    void insertSettings(@Param("memberId") String memberId);
    int updateSettings(Map<String, Object> settings);
    Map<String, Object> findHistoryByIdempotencyKey(@Param("memberId") String memberId,
                                                     @Param("idempotencyKey") String idempotencyKey);
    void insertHistory(Map<String, Object> history);
    int increaseCampaignResult(@Param("campaignId") String campaignId, @Param("amount") BigDecimal amount);
    List<Map<String, Object>> findHistoryByWalletId(@Param("walletId") String walletId);
    Map<String, Object> findWithdrawalByIdempotencyKey(@Param("memberId") String memberId,
                                                        @Param("idempotencyKey") String idempotencyKey);
    void insertWalletTransaction(Map<String, Object> transaction);
    List<Map<String, Object>> findTodayRoundUpCandidates();
    int insertRoundUp(Map<String, Object> roundUp);
    int completeRoundUp(@Param("roundupId") String roundupId);
    List<Map<String, Object>> findMonthlyAutoDonationCandidates(@Param("yearMonth") String yearMonth);
    int markAutoDonationCompleted(@Param("memberId") String memberId,
                                  @Param("yearMonth") String yearMonth);
}
