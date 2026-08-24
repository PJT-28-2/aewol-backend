package com.aewol.domain.donation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.donation.PotTransfer;
import com.aewol.domain.donation.dto.DonationDepositRequest;
import com.aewol.domain.donation.dto.DonationHistoryResponse;
import com.aewol.domain.donation.dto.DonationRequest;
import com.aewol.domain.donation.dto.DonationSettingRequest;
import com.aewol.domain.donation.dto.DonationWithdrawRequest;
import com.aewol.domain.donation.mapper.DonationMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
class DonationServiceImplTest {

    @Mock DonationMapper donationMapper;

    @Test
    @DisplayName("저금통과 활성 캠페인 및 설정을 화면 통합 형식으로 반환한다")
    void should_returnOverview_when_donationDataExists() {
        DonationServiceImpl service = service();
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")));
        when(donationMapper.findSettings("member-1")).thenReturn(settings(true, "1000", false));
        when(donationMapper.findMonthlySaved("pot-1")).thenReturn(new BigDecimal("3200"));
        when(donationMapper.findActiveCampaigns("member-1")).thenReturn(List.of(map(
                "id", "campaign-1", "organizationId", "organization-1",
                "organization", "테스트 보호소", "title", "난방비 지원",
                "category", "유기동물", "targetAmount", new BigDecimal("3000000"),
                "raised", new BigDecimal("2046000"), "participants", 312,
                "endsAt", LocalDateTime.now().plusDays(8), "preferred", 1)));

        var result = service.getOverview("member-1");

        assertEquals(new BigDecimal("12400"), result.getBalance());
        assertEquals(68, result.getCampaigns().get(0).getProgress());
        assertEquals("organization-1", result.getCampaigns().get(0).getOrganizationId());
        assertTrue(result.getCampaigns().get(0).isPreferred());
    }

    @Test
    @DisplayName("활성 기부처를 선호 기부처로 멱등 등록한다")
    void should_addPreference_when_organizationIsActive() {
        DonationServiceImpl service = service();
        when(donationMapper.findActiveOrganizationById("organization-1"))
                .thenReturn(map("organizationId", "organization-1"));

        var result = service.setPreference("member-1", "organization-1", true);

        assertTrue(result.isPreferred());
        assertEquals("organization-1", result.getOrganizationId());
        verify(donationMapper).insertPreference("member-1", "organization-1");
    }

    @Test
    @DisplayName("선호 기부처 해제는 등록 행이 없어도 성공한다")
    void should_removePreferenceIdempotently_when_organizationIsActive() {
        DonationServiceImpl service = service();
        when(donationMapper.findActiveOrganizationById("organization-1"))
                .thenReturn(map("organizationId", "organization-1"));

        var result = service.setPreference("member-1", "organization-1", false);

        assertFalse(result.isPreferred());
        verify(donationMapper).deletePreference("member-1", "organization-1");
    }

    @Test
    @DisplayName("비활성 또는 없는 기부처는 선호 등록하지 않는다")
    void should_rejectPreference_when_organizationIsNotActive() {
        DonationServiceImpl service = service();
        when(donationMapper.findActiveOrganizationById("organization-1")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.setPreference("member-1", "organization-1", true));

        assertEquals(404, exception.getStatus().value());
        verify(donationMapper, never()).insertPreference(anyString(), anyString());
    }

    @Test
    @DisplayName("[시연] 접두어가 있는 캠페인도 목록에 그대로 보여 준다")
    void should_includeDemoPrefixedCampaigns_inOverview() {
        DonationServiceImpl service = service();
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")));
        when(donationMapper.findSettings("member-1")).thenReturn(settings(true, "1000", false));
        when(donationMapper.findMonthlySaved("pot-1")).thenReturn(BigDecimal.ZERO);
        when(donationMapper.findActiveCampaigns("member-1")).thenReturn(List.of(
                map("id", "demo-1", "organization", "시연", "title", "[시연] 유기동물 구조·입양 활동 지원",
                        "category", "유기동물", "targetAmount", new BigDecimal("1"),
                        "raised", BigDecimal.ZERO, "participants", 0,
                        "endsAt", LocalDateTime.now().plusDays(8), "preferred", 0),
                map("id", "campaign-1", "organization", "테스트 보호소", "title", "난방비 지원",
                        "category", "유기동물", "targetAmount", new BigDecimal("3000000"),
                        "raised", new BigDecimal("2046000"), "participants", 312,
                        "endsAt", LocalDateTime.now().plusDays(8), "preferred", 1)));

        var overview = service.getOverview("member-1");
        assertEquals(2, overview.getCampaigns().size());
        assertEquals("[시연] 유기동물 구조·입양 활동 지원", overview.getCampaigns().get(0).getTitle());
        assertTrue(overview.getCampaigns().get(0).isDemo());
        assertTrue(overview.getCampaigns().get(0).isDonatable());
        assertEquals("난방비 지원", overview.getCampaigns().get(1).getTitle());
        assertFalse(overview.getCampaigns().get(1).isDemo());
        assertTrue(overview.getCampaigns().get(1).isDonatable());
    }

    @Test
    @DisplayName("운영에서는 시연 캠페인을 목록에 보여 주되 기부 불가로 표시한다")
    void should_markDemoCampaignNotDonatable_when_demoDonationsAreDisabled() {
        DonationServiceImpl service = service();
        ReflectionTestUtils.setField(service, "allowDemoDonations", false);
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")));
        when(donationMapper.findSettings("member-1")).thenReturn(settings(true, "1000", false));
        when(donationMapper.findMonthlySaved("pot-1")).thenReturn(BigDecimal.ZERO);
        when(donationMapper.findActiveCampaigns("member-1")).thenReturn(List.of(
                map("id", "demo-1", "organization", "시연", "title", "[시연] 유기동물 구조·입양 활동 지원",
                        "category", "유기동물", "targetAmount", new BigDecimal("1"),
                        "raised", BigDecimal.ZERO, "participants", 0,
                        "endsAt", LocalDateTime.now().plusDays(8), "preferred", 0)));

        var overview = service.getOverview("member-1");

        assertEquals(1, overview.getCampaigns().size());
        assertTrue(overview.getCampaigns().get(0).isDemo());
        assertFalse(overview.getCampaigns().get(0).isDonatable());
    }

    @Test
    @DisplayName("운영에서는 시연 캠페인에 직접 기부하지 않는다")
    void should_rejectDonate_when_demoCampaignAndDemoDonationsDisabled() {
        DonationServiceImpl service = service();
        ReflectionTestUtils.setField(service, "allowDemoDonations", false);
        DonationRequest request = donationRequest("3000", "demo-1", "request-1");
        when(donationMapper.findHistoryByIdempotencyKey("member-1", "request-1")).thenReturn(null);
        when(donationMapper.findCampaignById("demo-1")).thenReturn(map(
                "campaign_id", "demo-1", "title", "[시연] 유기동물 구조·입양 활동 지원"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.donate("member-1", request));

        assertEquals("시연 캠페인에는 기부할 수 없습니다.", exception.getMessage());
        verify(donationMapper, never()).decreasePotBalance(any(), any());
        verify(donationMapper, never()).insertHistory(anyMap());
    }

    @Test
    @DisplayName("시연 허용이면 [시연] 캠페인에도 기부할 수 있다")
    void should_donate_when_demoCampaignAndDemoDonationsEnabled() {
        DonationServiceImpl service = service();
        DonationRequest request = donationRequest("3000", "demo-1", "request-1");
        when(donationMapper.findHistoryByIdempotencyKey("member-1", "request-1")).thenReturn(null);
        when(donationMapper.findCampaignById("demo-1")).thenReturn(map(
                "campaign_id", "demo-1", "title", "[시연] 유기동물 구조·입양 활동 지원",
                "organization_id", "organization-1", "organization_name", "시연 보호소",
                "channel_id", "channel-1"));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")),
                        map("wallet_id", "pot-1", "balance", new BigDecimal("9400")));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")));
        when(donationMapper.decreasePotBalance("pot-1", new BigDecimal("3000"))).thenReturn(1);
        when(donationMapper.increaseCampaignResult("demo-1", new BigDecimal("3000"))).thenReturn(1);

        var result = service.donate("member-1", request);

        assertEquals(new BigDecimal("9400"), result.getBalance());
        verify(donationMapper).insertHistory(anyMap());
    }

    @Test
    @DisplayName("운영에서는 시연 캠페인을 자동 기부 대상으로 저장하지 않는다")
    void should_rejectAutoDonateSettings_when_demoCampaignAndDemoDonationsDisabled() {
        DonationServiceImpl service = service();
        ReflectionTestUtils.setField(service, "allowDemoDonations", false);
        DonationSettingRequest request = new DonationSettingRequest();
        ReflectionTestUtils.setField(request, "piggyBankEnabled", true);
        ReflectionTestUtils.setField(request, "savingUnit", new BigDecimal("1000"));
        ReflectionTestUtils.setField(request, "autoDonate", true);
        ReflectionTestUtils.setField(request, "campaignId", "demo-1");
        when(donationMapper.findSettings("member-1")).thenReturn(settings(true, "1000", false));
        when(donationMapper.findCampaignById("demo-1")).thenReturn(map(
                "title", "[시연] 유기동물 구조·입양 활동 지원", "organization_id", "organization-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.saveSettings("member-1", request));

        assertEquals("시연 캠페인은 자동 기부 대상으로 선택할 수 없습니다.", exception.getMessage());
        verify(donationMapper, never()).updateSettings(anyMap());
    }

    @Test
    @DisplayName("운영에서는 월말 자동기부가 시연 캠페인을 건너뛴다")
    void should_skipMonthlyAutoDonation_when_demoCampaignAndDemoDonationsDisabled() {
        DonationServiceImpl service = service();
        ReflectionTestUtils.setField(service, "allowDemoDonations", false);
        when(donationMapper.findMonthlyAutoDonationCandidates("2026-08")).thenReturn(List.of(map(
                "memberId", "member-1", "campaignId", "demo-1")));
        when(donationMapper.findSettings("member-1")).thenReturn(autoSettings("demo-1", null));
        when(donationMapper.findMainWalletForUpdate("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(donationMapper.findSettingsForUpdate("member-1")).thenReturn(autoSettings("demo-1", null));
        when(donationMapper.findHistoryByIdempotencyKey("member-1", "auto-member-1-2026-08"))
                .thenReturn(null);
        when(donationMapper.findCampaignById("demo-1")).thenReturn(map(
                "campaign_id", "demo-1", "title", "[시연] 유기동물 구조·입양 활동 지원"));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")));

        int completedCount = service.processMonthlyAutoDonations("2026-08");

        assertEquals(0, completedCount);
        verify(donationMapper, never()).decreasePotBalance(any(), any());
        verify(donationMapper, never()).insertHistory(anyMap());
        verify(donationMapper, never()).markAutoDonationCompleted(any(), any());
    }

    @Test
    @DisplayName("저금통이 없으면 빈 기부 내역을 반환한다")
    void should_returnEmptyHistory_when_potNotFound() {
        DonationServiceImpl service = service();
        when(donationMapper.findPotByMemberId("member-1")).thenReturn(null);

        assertTrue(service.getHistory("member-1").isEmpty());
        verify(donationMapper, never()).findHistoryByWalletId(any());
    }

    @Test
    @DisplayName("기부 내역을 camelCase DTO로 변환하고 표시명 스냅샷을 우선한다")
    void should_mapHistoryToDto_when_historyExists() {
        DonationServiceImpl service = service();
        when(donationMapper.findPotByMemberId("member-1")).thenReturn(map("wallet_id", "pot-1"));
        when(donationMapper.findHistoryByWalletId("pot-1")).thenReturn(List.of(
                map("donation_id", "d-1", "recipient_name", "스냅샷 보호소",
                        "organization_name", "현재 보호소", "campaign_title", "난방비 지원",
                        "amount", new BigDecimal("3000"), "status", "COMPLETED",
                        "receipt_url", "https://r/1",
                        "completed_at", LocalDateTime.of(2026, 8, 1, 10, 0),
                        "created_at", LocalDateTime.of(2026, 8, 1, 9, 0)),
                map("donation_id", "d-2", "organization_name", "현재 보호소",
                        "amount", new BigDecimal("500"), "status", "PENDING",
                        "created_at", LocalDateTime.of(2026, 8, 2, 9, 0))));

        List<DonationHistoryResponse> history = service.getHistory("member-1");

        assertEquals(2, history.size());
        // 기부 시점 표시명 스냅샷(recipient_name)을 우선 사용한다
        assertEquals("스냅샷 보호소", history.get(0).getOrganization());
        assertEquals("난방비 지원", history.get(0).getCampaignTitle());
        assertEquals(new BigDecimal("3000"), history.get(0).getAmount());
        // 스냅샷이 없으면 조인해온 현재 단체명으로 대체하고, 캠페인/완료시각 없으면 null이다
        assertEquals("현재 보호소", history.get(1).getOrganization());
        assertNull(history.get(1).getCampaignTitle());
        assertNull(history.get(1).getCompletedAt());
    }

    @Test
    @DisplayName("기부 요청이 유효하면 저금통을 차감하고 기부 내역을 한 번 저장한다")
    void should_donateOnce_when_requestIsValid() {
        DonationServiceImpl service = service();
        DonationRequest request = donationRequest("3000", "campaign-1", "request-1");
        when(donationMapper.findHistoryByIdempotencyKey("member-1", "request-1")).thenReturn(null);
        when(donationMapper.findCampaignById("campaign-1")).thenReturn(map(
                "campaign_id", "campaign-1", "organization_id", "organization-1",
                "organization_name", "테스트 보호소", "channel_id", "channel-1"));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")),
                        map("wallet_id", "pot-1", "balance", new BigDecimal("9400")));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")));
        when(donationMapper.decreasePotBalance("pot-1", new BigDecimal("3000"))).thenReturn(1);
        when(donationMapper.increaseCampaignResult("campaign-1", new BigDecimal("3000"))).thenReturn(1);

        var result = service.donate("member-1", request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(donationMapper).insertHistory(captor.capture());
        assertEquals("request-1", captor.getValue().get("idempotencyKey"));
        assertEquals(new BigDecimal("9400"), result.getBalance());
    }

    @Test
    @DisplayName("저금통 잔액이 부족하면 기부 내역을 저장하지 않는다")
    void should_throwException_when_balanceIsInsufficient() {
        DonationServiceImpl service = service();
        DonationRequest request = donationRequest("3000", "campaign-1", "request-1");
        when(donationMapper.findHistoryByIdempotencyKey("member-1", "request-1")).thenReturn(null);
        when(donationMapper.findCampaignById("campaign-1")).thenReturn(map(
                "campaign_id", "campaign-1", "organization_id", "organization-1",
                "organization_name", "테스트 보호소"));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("1000")));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("1000")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.donate("member-1", request));

        assertEquals("저금통 잔액이 부족합니다.", exception.getMessage());
        verify(donationMapper).insertHistory(anyMap());
        verify(donationMapper, never()).increaseCampaignResult(any(), any());
    }

    @Test
    @DisplayName("기부 멱등키가 없으면 저금통을 차감하지 않는다")
    void should_rejectDonate_whenIdempotencyKeyIsMissing() {
        DonationServiceImpl service = service();
        DonationRequest request = donationRequest("3000", "campaign-1", "  ");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.donate("member-1", request));

        assertEquals("중복 요청 방지 키를 입력해 주세요.", exception.getMessage());
        verify(donationMapper, never()).findHistoryByIdempotencyKey(any(), any());
        verify(donationMapper, never()).decreasePotBalance(any(), any());
    }

    @Test
    @DisplayName("같은 기부 키로 재시도하면 이전 성공을 반환하고 다시 차감하지 않는다")
    void should_returnExistingDonation_whenIdempotencyKeyIsReused() {
        DonationServiceImpl service = service();
        DonationRequest request = donationRequest("3000", "campaign-1", "request-1");
        when(donationMapper.findHistoryByIdempotencyKey("member-1", "request-1"))
                .thenReturn(map("donation_id", "donation-1", "amount", new BigDecimal("3000"),
                        "campaign_id", "campaign-1"));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("9400")));

        var result = service.donate("member-1", request);

        assertEquals("donation-1", result.getDonationId());
        assertEquals(new BigDecimal("9400"), result.getBalance());
        verify(donationMapper, never()).decreasePotBalance(any(), any());
        verify(donationMapper, never()).insertHistory(anyMap());
    }

    @Test
    @DisplayName("동일한 기부 키에 다른 금액을 요청하면 충돌로 거절한다")
    void should_throwConflict_whenSameDonationKeyHasDifferentAmount() {
        DonationServiceImpl service = service();
        when(donationMapper.findHistoryByIdempotencyKey("member-1", "request-1"))
                .thenReturn(map("donation_id", "donation-1", "amount", new BigDecimal("3000"),
                        "campaign_id", "campaign-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.donate("member-1", donationRequest("5000", "campaign-1", "request-1")));

        assertEquals(409, exception.getStatus().value());
        verify(donationMapper, never()).decreasePotBalance(any(), any());
        verify(donationMapper, never()).insertHistory(anyMap());
    }

    @Test
    @DisplayName("동일한 기부 키에 다른 캠페인을 요청하면 충돌로 거절한다")
    void should_throwConflict_whenSameDonationKeyHasDifferentCampaign() {
        DonationServiceImpl service = service();
        when(donationMapper.findHistoryByIdempotencyKey("member-1", "request-1"))
                .thenReturn(map("donation_id", "donation-1", "amount", new BigDecimal("3000"),
                        "campaign_id", "campaign-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.donate("member-1", donationRequest("3000", "campaign-2", "request-1")));

        assertEquals(409, exception.getStatus().value());
        verify(donationMapper, never()).decreasePotBalance(any(), any());
        verify(donationMapper, never()).insertHistory(anyMap());
    }

    @Test
    @DisplayName("저금통 잠금 뒤에 같은 키가 있으면 차감 없이 기존 기부를 반환한다")
    void should_returnExistingDonation_whenKeyAppearsAfterPotLock() {
        DonationServiceImpl service = service();
        DonationRequest request = donationRequest("3000", "campaign-1", "request-1");
        when(donationMapper.findHistoryByIdempotencyKey("member-1", "request-1"))
                .thenReturn(null)
                .thenReturn(map("donation_id", "donation-1", "amount", new BigDecimal("3000"),
                        "campaign_id", "campaign-1"));
        when(donationMapper.findCampaignById("campaign-1")).thenReturn(map(
                "campaign_id", "campaign-1", "organization_id", "organization-1",
                "organization_name", "테스트 보호소", "channel_id", "channel-1"));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("9400")));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("9400")));

        var result = service.donate("member-1", request);

        assertEquals("donation-1", result.getDonationId());
        assertEquals(new BigDecimal("9400"), result.getBalance());
        verify(donationMapper, never()).decreasePotBalance(any(), any());
        verify(donationMapper, never()).insertHistory(anyMap());
    }

    @Test
    @DisplayName("기부 내역 유니크 충돌이면 오류 대신 기존 기부를 반환한다")
    void should_returnExistingDonation_whenConcurrentInsertHitsUniqueKey() {
        DonationServiceImpl service = service();
        DonationRequest request = donationRequest("3000", "campaign-1", "request-1");
        when(donationMapper.findHistoryByIdempotencyKey("member-1", "request-1"))
                .thenReturn(null)
                .thenReturn(null)
                .thenReturn(map("donation_id", "donation-1", "amount", new BigDecimal("3000"),
                        "campaign_id", "campaign-1"));
        when(donationMapper.findCampaignById("campaign-1")).thenReturn(map(
                "campaign_id", "campaign-1", "organization_id", "organization-1",
                "organization_name", "테스트 보호소", "channel_id", "channel-1"));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("9400")));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("9400")));
        doThrow(new org.springframework.dao.DuplicateKeyException("duplicate"))
                .when(donationMapper).insertHistory(anyMap());

        var result = service.donate("member-1", request);

        assertEquals("donation-1", result.getDonationId());
        assertEquals(new BigDecimal("9400"), result.getBalance());
        verify(donationMapper, never()).decreasePotBalance(any(), any());
    }

    @Test
    @DisplayName("출금 응답이 유실되어 같은 키로 재시도해도 한 번만 이체한다")
    void should_transferOnce_when_withdrawRequestIsRetried() {
        DonationServiceImpl service = service();
        DonationWithdrawRequest request = withdrawRequest("2000", "withdraw-1");
        when(donationMapper.findMainWalletForUpdate("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")),
                        map("wallet_id", "pot-1", "balance", new BigDecimal("10400")),
                        map("wallet_id", "pot-1", "balance", new BigDecimal("10400")));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")),
                        map("wallet_id", "pot-1", "balance", new BigDecimal("10400")));
        when(donationMapper.findWithdrawalByIdempotencyKey("member-1", PotTransfer.withdrawKey("withdraw-1")))
                .thenReturn(null, map("price", money("2000")));
        when(donationMapper.decreasePotBalance("pot-1", money("2000"))).thenReturn(1);
        when(donationMapper.increaseWalletBalance("wallet-1", money("2000"))).thenReturn(1);

        var firstResult = service.withdraw("member-1", request);
        var retriedResult = service.withdraw("member-1", request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(donationMapper, times(1)).decreasePotBalance("pot-1", money("2000"));
        verify(donationMapper, times(1)).increaseWalletBalance("wallet-1", money("2000"));
        verify(donationMapper, times(1)).insertWalletTransaction(captor.capture());
        assertEquals(PotTransfer.withdrawKey("withdraw-1"), captor.getValue().get("idempotencyKey"));
        assertEquals(PotTransfer.PURPOSE_WITHDRAW, captor.getValue().get("transferPurpose"));
        assertEquals("pot-1", captor.getValue().get("sourceWalletId"));
        assertEquals("wallet-1", captor.getValue().get("counterWalletId"));
        assertEquals(new BigDecimal("10400"), firstResult.getBalance());
        assertEquals(new BigDecimal("10400"), retriedResult.getBalance());
    }

    @Test
    @DisplayName("동일한 출금 키에 다른 금액을 요청하면 충돌로 거절한다")
    void should_throwConflict_when_sameWithdrawalKeyHasDifferentAmount() {
        DonationServiceImpl service = service();
        DonationWithdrawRequest request = withdrawRequest("3000", "withdraw-1");
        when(donationMapper.findMainWalletForUpdate("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("10400")));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("10400")));
        when(donationMapper.findWithdrawalByIdempotencyKey("member-1", PotTransfer.withdrawKey("withdraw-1")))
                .thenReturn(map("price", money("2000")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", request));

        assertEquals(409, exception.getStatus().value());
        verify(donationMapper, never()).decreasePotBalance(any(), any());
        verify(donationMapper, never()).insertWalletTransaction(anyMap());
    }

    @Test
    @DisplayName("넣기 응답이 유실되어 같은 키로 재시도해도 한 번만 이체한다")
    void should_transferOnce_when_depositRequestIsRetried() {
        DonationServiceImpl service = service();
        DonationDepositRequest request = depositRequest("2000", "deposit-1");
        when(donationMapper.findMainWalletForUpdate("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")),
                        map("wallet_id", "pot-1", "balance", new BigDecimal("14400")),
                        map("wallet_id", "pot-1", "balance", new BigDecimal("14400")));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")),
                        map("wallet_id", "pot-1", "balance", new BigDecimal("14400")));
        when(donationMapper.findDepositByIdempotencyKey("member-1", PotTransfer.depositKey("deposit-1")))
                .thenReturn(null, map("price", money("2000")));
        when(donationMapper.decreaseMainWalletBalance("wallet-1", money("2000"))).thenReturn(1);
        when(donationMapper.increasePotBalance("pot-1", money("2000"))).thenReturn(1);

        var firstResult = service.deposit("member-1", request);
        var retriedResult = service.deposit("member-1", request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(donationMapper, times(1)).decreaseMainWalletBalance("wallet-1", money("2000"));
        verify(donationMapper, times(1)).increasePotBalance("pot-1", money("2000"));
        verify(donationMapper, times(1)).insertWalletTransaction(captor.capture());
        assertEquals(PotTransfer.depositKey("deposit-1"), captor.getValue().get("idempotencyKey"));
        assertEquals(PotTransfer.PURPOSE_DEPOSIT, captor.getValue().get("transferPurpose"));
        assertEquals("wallet-1", captor.getValue().get("sourceWalletId"));
        assertEquals("pot-1", captor.getValue().get("counterWalletId"));
        assertEquals(new BigDecimal("14400"), firstResult.getBalance());
        assertEquals(new BigDecimal("14400"), retriedResult.getBalance());
    }

    @Test
    @DisplayName("지갑 잔액이 부족하면 저금통에 넣지 않는다")
    void should_throwException_when_walletBalanceIsInsufficientForDeposit() {
        DonationServiceImpl service = service();
        DonationDepositRequest request = depositRequest("3000", "deposit-1");
        when(donationMapper.findMainWalletForUpdate("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("1000")));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("1000")));
        when(donationMapper.findDepositByIdempotencyKey("member-1", PotTransfer.depositKey("deposit-1"))).thenReturn(null);
        when(donationMapper.decreaseMainWalletBalance("wallet-1", money("3000"))).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.deposit("member-1", request));

        assertEquals("지갑 잔액이 부족합니다.", exception.getMessage());
        verify(donationMapper, never()).increasePotBalance(any(), any());
        verify(donationMapper, never()).insertWalletTransaction(anyMap());
    }

    @Test
    @DisplayName("저금통 설정은 화면에서 허용한 저금 단위와 자동 기부 캠페인을 저장한다")
    void should_saveSettings_when_valuesAreValid() {
        DonationServiceImpl service = service();
        DonationSettingRequest request = new DonationSettingRequest();
        ReflectionTestUtils.setField(request, "piggyBankEnabled", true);
        ReflectionTestUtils.setField(request, "savingUnit", new BigDecimal("1000"));
        ReflectionTestUtils.setField(request, "autoDonate", true);
        ReflectionTestUtils.setField(request, "campaignId", "campaign-1");
        when(donationMapper.findSettings("member-1")).thenReturn(
                settings(true, "1000", false), settings(true, "1000", true));
        when(donationMapper.findCampaignById("campaign-1"))
                .thenReturn(map("organization_id", "organization-1"));
        when(donationMapper.updateSettings(anyMap())).thenReturn(1);

        var result = service.saveSettings("member-1", request);

        assertTrue(result.isAutoDonate());
        verify(donationMapper).updateSettings(anyMap());
    }

    // 잔돈 적립(processDailyRoundUps) 테스트는 DonationRoundUpExecutorTest로 옮겼다 — 그 로직이
    // DonationServiceImpl에서 DonationRoundUpExecutor로 이동했다(#349, 건별 독립 트랜잭션).

    @Test
    @DisplayName("월말 자동 기부 대상이면 저금통 전액을 선택 캠페인에 한 번 기부한다")
    void should_donateEntirePot_when_monthlyAutoDonationIsEnabled() {
        DonationServiceImpl service = service();
        when(donationMapper.findMonthlyAutoDonationCandidates("2026-08")).thenReturn(List.of(map(
                "memberId", "member-1", "campaignId", "campaign-1")));
        when(donationMapper.findSettings("member-1")).thenReturn(autoSettings("campaign-1", null));
        when(donationMapper.findMainWalletForUpdate("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(donationMapper.findSettingsForUpdate("member-1")).thenReturn(autoSettings("campaign-1", null));
        when(donationMapper.findHistoryByIdempotencyKey("member-1", "auto-member-1-2026-08"))
                .thenReturn(null);
        when(donationMapper.findCampaignById("campaign-1")).thenReturn(map(
                "campaign_id", "campaign-1", "organization_id", "organization-1",
                "organization_name", "테스트 보호소", "channel_id", "channel-1"));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")),
                        map("wallet_id", "pot-1", "balance", BigDecimal.ZERO),
                        map("wallet_id", "pot-1", "balance", BigDecimal.ZERO));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")),
                        map("wallet_id", "pot-1", "balance", new BigDecimal("12400")));
        when(donationMapper.decreasePotBalance("pot-1", new BigDecimal("12400"))).thenReturn(1);
        when(donationMapper.increaseCampaignResult("campaign-1", new BigDecimal("12400"))).thenReturn(1);
        when(donationMapper.markAutoDonationCompleted("member-1", "2026-08")).thenReturn(1);

        int completedCount = service.processMonthlyAutoDonations("2026-08");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(donationMapper).insertHistory(captor.capture());
        assertEquals("auto-member-1-2026-08", captor.getValue().get("idempotencyKey"));
        assertEquals(new BigDecimal("12400"), captor.getValue().get("amount"));
        assertEquals(1, completedCount);

        InOrder lockOrder = inOrder(donationMapper);
        lockOrder.verify(donationMapper).findSettings("member-1");
        lockOrder.verify(donationMapper).findMainWalletForUpdate("member-1");
        lockOrder.verify(donationMapper).findPotForUpdate("member-1");
        lockOrder.verify(donationMapper).findSettingsForUpdate("member-1");
    }

    @Test
    @DisplayName("수동 넣기는 자동 절삭 키와 같아도 네임스페이스를 붙여 별도 거래로 저장한다")
    void should_namespaceDepositKey_when_clientSendsSpareTrimKey() {
        DonationServiceImpl service = service();
        String spareTrimKey = PotTransfer.spareTrimKey("member-1", java.time.LocalDate.parse("2026-08-25"));
        DonationDepositRequest request = depositRequest("2000", spareTrimKey);
        when(donationMapper.findMainWalletForUpdate("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("1000")),
                        map("wallet_id", "pot-1", "balance", new BigDecimal("3000")));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("1000")));
        when(donationMapper.findDepositByIdempotencyKey("member-1", PotTransfer.depositKey(spareTrimKey)))
                .thenReturn(null);
        when(donationMapper.decreaseMainWalletBalance("wallet-1", money("2000"))).thenReturn(1);
        when(donationMapper.increasePotBalance("pot-1", money("2000"))).thenReturn(1);

        service.deposit("member-1", request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(donationMapper).insertWalletTransaction(captor.capture());
        assertEquals(PotTransfer.depositKey(spareTrimKey), captor.getValue().get("idempotencyKey"));
        assertEquals(PotTransfer.PURPOSE_DEPOSIT, captor.getValue().get("transferPurpose"));
        verify(donationMapper).findDepositByIdempotencyKey("member-1", PotTransfer.depositKey(spareTrimKey));
        verify(donationMapper, never()).findDepositByIdempotencyKey("member-1", spareTrimKey);
    }

    @Test
    @DisplayName("소수점 셋째 자리 넣기 금액은 서비스에서도 거절한다")
    void should_rejectDeposit_when_amountHasThreeDecimalPlaces() {
        DonationServiceImpl service = service();
        DonationDepositRequest request = depositRequest("1.001", "deposit-1");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.deposit("member-1", request));

        assertEquals("넣을 금액은 소수점 둘째 자리까지만 입력할 수 있습니다.", exception.getMessage());
        verify(donationMapper, never()).findMainWalletForUpdate(any());
    }

    @Test
    @DisplayName("DECIMAL(15,2) 범위를 넘는 넣기 금액은 거절한다")
    void should_rejectDeposit_when_amountExceedsDatabasePrecision() {
        DonationServiceImpl service = service();
        DonationDepositRequest request = depositRequest("10000000000000.00", "deposit-1");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.deposit("member-1", request));

        assertEquals("넣을 금액이 허용 범위를 초과했습니다.", exception.getMessage());
        verify(donationMapper, never()).findMainWalletForUpdate(any());
    }

    @Test
    @DisplayName("소수점 셋째 자리 출금 금액은 서비스에서도 거절한다")
    void should_rejectWithdraw_when_amountHasThreeDecimalPlaces() {
        DonationServiceImpl service = service();
        DonationWithdrawRequest request = withdrawRequest("1.001", "withdraw-1");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", request));

        assertEquals("출금 금액은 소수점 둘째 자리까지만 입력할 수 있습니다.", exception.getMessage());
        verify(donationMapper, never()).findMainWalletForUpdate(any());
    }

    private DonationRequest donationRequest(String amount, String campaignId, String key) {
        DonationRequest request = new DonationRequest();
        ReflectionTestUtils.setField(request, "amount", new BigDecimal(amount));
        ReflectionTestUtils.setField(request, "campaignId", campaignId);
        ReflectionTestUtils.setField(request, "idempotencyKey", key);
        return request;
    }

    private DonationWithdrawRequest withdrawRequest(String amount, String key) {
        DonationWithdrawRequest request = new DonationWithdrawRequest();
        ReflectionTestUtils.setField(request, "amount", new BigDecimal(amount));
        ReflectionTestUtils.setField(request, "idempotencyKey", key);
        return request;
    }

    private DonationDepositRequest depositRequest(String amount, String key) {
        DonationDepositRequest request = new DonationDepositRequest();
        ReflectionTestUtils.setField(request, "amount", new BigDecimal(amount));
        ReflectionTestUtils.setField(request, "idempotencyKey", key);
        return request;
    }

    private Map<String, Object> settings(boolean piggyBank, String savingUnit, boolean autoDonate) {
        return map("piggyBankEnabled", piggyBank, "savingUnit", new BigDecimal(savingUnit),
                "autoDonate", autoDonate);
    }

    private Map<String, Object> autoSettings(String campaignId, String lastYearMonth) {
        return map("piggyBankEnabled", true, "savingUnit", new BigDecimal("1000"),
                "autoDonate", true, "auto_donate_enabled", 1,
                "autoDonateCampaignId", campaignId, "auto_donate_campaign_id", campaignId,
                "last_auto_donated_year_month", lastYearMonth);
    }

    private DonationServiceImpl service() {
        return new DonationServiceImpl(donationMapper, TransactionOperations.withoutTransaction());
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
