package com.aewol.domain.donation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
                "id", "campaign-1", "organization", "테스트 보호소", "title", "난방비 지원",
                "category", "유기동물", "targetAmount", new BigDecimal("3000000"),
                "raised", new BigDecimal("2046000"), "participants", 312,
                "endsAt", LocalDateTime.now().plusDays(8), "preferred", 1)));

        var result = service.getOverview("member-1");

        assertEquals(new BigDecimal("12400"), result.getBalance());
        assertEquals(68, result.getCampaigns().get(0).getProgress());
        assertTrue(result.getCampaigns().get(0).isPreferred());
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
        verify(donationMapper, never()).insertHistory(anyMap());
    }

    @Test
    @DisplayName("출금하면 저금통을 차감하고 지갑 잔액과 거래 내역을 반영한다")
    void should_transferToWallet_when_withdrawRequestIsValid() {
        DonationServiceImpl service = service();
        DonationWithdrawRequest request = new DonationWithdrawRequest();
        ReflectionTestUtils.setField(request, "amount", new BigDecimal("2000"));
        when(donationMapper.findMainWalletByMemberId("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")),
                        map("wallet_id", "pot-1", "balance", new BigDecimal("10400")));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")));
        when(donationMapper.decreasePotBalance("pot-1", new BigDecimal("2000"))).thenReturn(1);
        when(donationMapper.increaseWalletBalance("wallet-1", new BigDecimal("2000"))).thenReturn(1);

        var result = service.withdraw("member-1", request);

        verify(donationMapper).insertWalletTransaction(anyMap());
        assertEquals(new BigDecimal("10400"), result.getBalance());
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

    @Test
    @DisplayName("오늘 결제 금액의 단위 미만 잔돈을 저금통에 한 번 적립한다")
    void should_saveRoundUpAmount_when_paymentHasRemainder() {
        DonationServiceImpl service = service();
        when(donationMapper.findTodayRoundUpCandidates()).thenReturn(List.of(map(
                "txnId", "txn-1", "memberId", "member-1",
                "amount", new BigDecimal("12600"), "savingUnit", new BigDecimal("1000"))));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", BigDecimal.ZERO));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", BigDecimal.ZERO));
        when(donationMapper.insertRoundUp(anyMap())).thenReturn(1);
        when(donationMapper.increasePotBalance("pot-1", new BigDecimal("600"))).thenReturn(1);
        when(donationMapper.completeRoundUp(any())).thenReturn(1);

        int completedCount = service.processDailyRoundUps();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(donationMapper).insertRoundUp(captor.capture());
        assertEquals(new BigDecimal("600"), captor.getValue().get("roundupAmount"));
        assertEquals(1, completedCount);
    }

    @Test
    @DisplayName("결제 금액이 저금 단위로 나누어떨어지면 차액 없이 처리 완료한다")
    void should_skipSaving_when_paymentHasNoRemainder() {
        DonationServiceImpl service = service();
        when(donationMapper.findTodayRoundUpCandidates()).thenReturn(List.of(map(
                "txnId", "txn-1", "memberId", "member-1",
                "amount", new BigDecimal("12000"), "savingUnit", new BigDecimal("1000"))));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", BigDecimal.ZERO));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", BigDecimal.ZERO));
        when(donationMapper.insertRoundUp(anyMap())).thenReturn(1);

        int completedCount = service.processDailyRoundUps();

        verify(donationMapper, never()).increasePotBalance(any(), any());
        assertEquals(0, completedCount);
    }

    @Test
    @DisplayName("월말 자동 기부 대상이면 저금통 전액을 선택 캠페인에 한 번 기부한다")
    void should_donateEntirePot_when_monthlyAutoDonationIsEnabled() {
        DonationServiceImpl service = service();
        when(donationMapper.findMonthlyAutoDonationCandidates("2026-08")).thenReturn(List.of(map(
                "memberId", "member-1", "campaignId", "campaign-1")));
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
    }

    private DonationRequest donationRequest(String amount, String campaignId, String key) {
        DonationRequest request = new DonationRequest();
        ReflectionTestUtils.setField(request, "amount", new BigDecimal(amount));
        ReflectionTestUtils.setField(request, "campaignId", campaignId);
        ReflectionTestUtils.setField(request, "idempotencyKey", key);
        return request;
    }

    private Map<String, Object> settings(boolean piggyBank, String savingUnit, boolean autoDonate) {
        return map("piggyBankEnabled", piggyBank, "savingUnit", new BigDecimal(savingUnit),
                "autoDonate", autoDonate);
    }

    private DonationServiceImpl service() {
        return new DonationServiceImpl(donationMapper);
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
