package com.aewol.domain.donation.service;

import com.aewol.domain.donation.dto.*;
import java.util.List;
import java.util.Map;

public interface DonationService {
    DonationOverviewResponse getOverview(String memberId);
    DonationBalanceResponse donate(String memberId, DonationRequest request);
    DonationBalanceResponse withdraw(String memberId, DonationWithdrawRequest request);
    DonationSettingsResponse saveSettings(String memberId, DonationSettingRequest request);
    List<Map<String, Object>> getHistory(String memberId);
    int processDailyRoundUps();
    int processMonthlyAutoDonations(String yearMonth);
}
