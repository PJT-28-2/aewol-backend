package com.aewol.domain.donation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
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
    @DisplayName("운영에서는 [시연] 캠페인을 목록과 기부 대상에서 뺀다")
    void should_hideDemoCampaigns_when_flagDisabled() {
        DonationServiceImpl service = service();
        ReflectionTestUtils.setField(service, "showDemoCampaigns", false);
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
        assertEquals(1, overview.getCampaigns().size());
        assertEquals("난방비 지원", overview.getCampaigns().get(0).getTitle());
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
        verify(donationMapper, never()).insertHistory(anyMap());
    }

    @Test
    @DisplayName("출금 응답이 유실되어 같은 키로 재시도해도 한 번만 이체한다")
    void should_transferOnce_when_withdrawRequestIsRetried() {
        DonationServiceImpl service = service();
        DonationWithdrawRequest request = withdrawRequest("2000", "withdraw-1");
        when(donationMapper.findMainWalletByMemberId("member-1")).thenReturn(map("wallet_id", "wallet-1"));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")),
                        map("wallet_id", "pot-1", "balance", new BigDecimal("10400")),
                        map("wallet_id", "pot-1", "balance", new BigDecimal("10400")));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("12400")),
                        map("wallet_id", "pot-1", "balance", new BigDecimal("10400")));
        when(donationMapper.findWithdrawalByIdempotencyKey("member-1", "withdraw-1"))
                .thenReturn(null, map("price", new BigDecimal("2000")));
        when(donationMapper.decreasePotBalance("pot-1", new BigDecimal("2000"))).thenReturn(1);
        when(donationMapper.increaseWalletBalance("wallet-1", new BigDecimal("2000"))).thenReturn(1);

        var firstResult = service.withdraw("member-1", request);
        var retriedResult = service.withdraw("member-1", request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(donationMapper, times(1)).decreasePotBalance("pot-1", new BigDecimal("2000"));
        verify(donationMapper, times(1)).increaseWalletBalance("wallet-1", new BigDecimal("2000"));
        verify(donationMapper, times(1)).insertWalletTransaction(captor.capture());
        assertEquals("withdraw-1", captor.getValue().get("idempotencyKey"));
        assertEquals(new BigDecimal("10400"), firstResult.getBalance());
        assertEquals(new BigDecimal("10400"), retriedResult.getBalance());
    }

    @Test
    @DisplayName("동일한 출금 키에 다른 금액을 요청하면 충돌로 거절한다")
    void should_throwConflict_when_sameWithdrawalKeyHasDifferentAmount() {
        DonationServiceImpl service = service();
        DonationWithdrawRequest request = withdrawRequest("3000", "withdraw-1");
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("10400")));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", new BigDecimal("10400")));
        when(donationMapper.findWithdrawalByIdempotencyKey("member-1", "withdraw-1"))
                .thenReturn(map("price", new BigDecimal("2000")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.withdraw("member-1", request));

        assertEquals(409, exception.getStatus().value());
        verify(donationMapper, never()).decreasePotBalance(any(), any());
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

    @Test
    @DisplayName("결제 금액을 저금 단위로 올린 차액을 저금통에 한 번 적립한다")
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
        when(donationMapper.increasePotBalance("pot-1", new BigDecimal("400"))).thenReturn(1);
        when(donationMapper.completeRoundUp(any())).thenReturn(1);

        int completedCount = service.processDailyRoundUps();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(donationMapper).insertRoundUp(captor.capture());
        assertEquals(new BigDecimal("400"), captor.getValue().get("roundupAmount"));
        assertEquals(1, completedCount);
    }

    @Test
    @DisplayName("결제 금액이 저금 단위로 딱 떨어지면 올릴 차액이 없어 적립하지 않는다")
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

    // 예전에는 나머지를 적립했다(34,800원이면 800원). 화면 문구가 "결제 잔돈 자동 적립"이라
    // 올림 차액(200원)을 기대하게 되는데 실제로는 반대여서 3배 넘게 차이가 났다.
    @Test
    @DisplayName("나머지가 아니라 올림 차액을 적립한다")
    void should_saveGapToNextUnit_notRemainder() {
        DonationServiceImpl service = service();
        when(donationMapper.findTodayRoundUpCandidates()).thenReturn(List.of(map(
                "txnId", "txn-1", "memberId", "member-1",
                "amount", new BigDecimal("34800"), "savingUnit", new BigDecimal("1000"))));
        when(donationMapper.findPotByMemberId("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", BigDecimal.ZERO));
        when(donationMapper.findPotForUpdate("member-1"))
                .thenReturn(map("wallet_id", "pot-1", "balance", BigDecimal.ZERO));
        when(donationMapper.insertRoundUp(anyMap())).thenReturn(1);
        when(donationMapper.increasePotBalance(any(), any())).thenReturn(1);
        when(donationMapper.completeRoundUp(any())).thenReturn(1);

        service.processDailyRoundUps();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(donationMapper).insertRoundUp(captor.capture());
        assertEquals(new BigDecimal("200"), captor.getValue().get("roundupAmount"));
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

    private DonationWithdrawRequest withdrawRequest(String amount, String key) {
        DonationWithdrawRequest request = new DonationWithdrawRequest();
        ReflectionTestUtils.setField(request, "amount", new BigDecimal(amount));
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
