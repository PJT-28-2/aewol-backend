package com.aewol.domain.donation.service;

import com.aewol.domain.donation.dto.*;
import java.util.List;
import java.util.Map;

public interface DonationService {
    DonationOverviewResponse getOverview(String memberId);
    DonationBalanceResponse donate(String memberId, DonationRequest request);
    DonationBalanceResponse deposit(String memberId, DonationDepositRequest request);
    DonationBalanceResponse withdraw(String memberId, DonationWithdrawRequest request);
    DonationSettingsResponse saveSettings(String memberId, DonationSettingRequest request);
    DonationPreferenceResponse setPreference(String memberId, String organizationId, boolean preferred);
    List<DonationHistoryResponse> getHistory(String memberId);
    int processMonthlyAutoDonations(String yearMonth);
}
