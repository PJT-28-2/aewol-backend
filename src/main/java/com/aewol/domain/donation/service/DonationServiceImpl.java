package com.aewol.domain.donation.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.donation.dto.*;
import com.aewol.domain.donation.mapper.DonationMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DonationServiceImpl implements DonationService {

    private static final Set<BigDecimal> SAVING_UNITS = Set.of(
            BigDecimal.TEN, BigDecimal.valueOf(100), BigDecimal.valueOf(1000));

    private final DonationMapper donationMapper;

    @Override
    @Transactional
    public DonationOverviewResponse getOverview(String memberId) {
        memberId = requireMemberId(memberId);
        Map<String, Object> pot = getOrCreatePot(memberId);
        Map<String, Object> settings = getOrCreateSettings(memberId);
        BigDecimal balance = decimal(pot, "balance");
        BigDecimal monthlySaved = Optional.ofNullable(
                donationMapper.findMonthlySaved(text(pot, "wallet_id", "walletId")))
                .orElse(BigDecimal.ZERO);
        List<DonationCampaignResponse> campaigns = donationMapper.findActiveCampaigns(memberId).stream()
                .map(this::toCampaignResponse)
                .collect(Collectors.toList());

        return DonationOverviewResponse.builder()
                .balance(balance)
                .monthlySaved(monthlySaved)
                .impactMessage(buildImpactMessage(balance))
                .campaigns(campaigns)
                .settings(toSettingsResponse(settings))
                .build();
    }

    @Override
    @Transactional
    public DonationBalanceResponse donate(String memberId, DonationRequest request) {
        memberId = requireMemberId(memberId);
        String idempotencyKey = request.getIdempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = UUID.randomUUID().toString();
        } else if (idempotencyKey.length() > 64) {
            throw new BusinessException("중복 요청 방지 키는 64자 이하여야 합니다.");
        }

        Map<String, Object> existing = donationMapper.findHistoryByIdempotencyKey(memberId, idempotencyKey);
        if (existing != null) {
            Map<String, Object> currentPot = getOrCreatePot(memberId);
            return DonationBalanceResponse.builder()
                    .donationId(text(existing, "donation_id", "donationId"))
                    .balance(decimal(currentPot, "balance"))
                    .build();
        }

        Map<String, Object> campaign = donationMapper.findCampaignById(request.getCampaignId());
        if (campaign == null) throw BusinessException.notFound("진행 중인 기부 캠페인을 찾을 수 없습니다.");
        return donateToCampaign(memberId, campaign, request.getAmount(), idempotencyKey);
    }

    private DonationBalanceResponse donateToCampaign(String memberId, Map<String, Object> campaign,
                                                       BigDecimal amount, String idempotencyKey) {
        Map<String, Object> pot = getOrCreatePotForUpdate(memberId);
        String walletId = text(pot, "wallet_id", "walletId");
        if (donationMapper.decreasePotBalance(walletId, amount) != 1) {
            throw new BusinessException("저금통 잔액이 부족합니다.");
        }

        Map<String, Object> history = new HashMap<>();
        history.put("walletId", walletId);
        history.put("organizationId", text(campaign, "organization_id", "organizationId"));
        history.put("campaignId", text(campaign, "campaign_id", "campaignId"));
        history.put("channelId", text(campaign, "channel_id", "channelId"));
        history.put("amount", amount);
        history.put("recipientName", text(campaign, "organization_name", "organizationName"));
        history.put("idempotencyKey", idempotencyKey);
        donationMapper.insertHistory(history);
        String campaignId = text(campaign, "campaign_id", "campaignId");
        if (donationMapper.increaseCampaignResult(campaignId, amount) != 1) {
            throw BusinessException.conflict("캠페인 모금액을 반영하지 못했습니다.");
        }

        Map<String, Object> updatedPot = donationMapper.findPotByMemberId(memberId);
        return DonationBalanceResponse.builder()
                .donationId(text(history, "donationId"))
                .balance(decimal(updatedPot, "balance"))
                .build();
    }

    @Override
    @Transactional
    public DonationBalanceResponse withdraw(String memberId, DonationWithdrawRequest request) {
        memberId = requireMemberId(memberId);
        String idempotencyKey = requireIdempotencyKey(request.getIdempotencyKey());
        Map<String, Object> pot = getOrCreatePotForUpdate(memberId);
        String donationWalletId = text(pot, "wallet_id", "walletId");

        Map<String, Object> existing = donationMapper.findWithdrawalByIdempotencyKey(memberId, idempotencyKey);
        if (existing != null) {
            if (decimal(existing, "price").compareTo(request.getAmount()) != 0) {
                throw BusinessException.conflict("동일한 중복 요청 방지 키에 다른 출금 금액을 사용할 수 없습니다.");
            }
            return DonationBalanceResponse.builder()
                    .balance(decimal(pot, "balance"))
                    .build();
        }

        Map<String, Object> wallet = donationMapper.findMainWalletByMemberId(memberId);
        if (wallet == null) throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        if (donationMapper.decreasePotBalance(donationWalletId, request.getAmount()) != 1) {
            throw new BusinessException("저금통 잔액이 부족합니다.");
        }

        String walletId = text(wallet, "wallet_id", "walletId");
        if (donationMapper.increaseWalletBalance(walletId, request.getAmount()) != 1) {
            throw BusinessException.conflict("지갑 잔액을 반영하지 못했습니다.");
        }
        // 저금통(DONATION) -> 실지갑(MAIN) 내부 이체 원장 기록
        Map<String, Object> transaction = new HashMap<>();
        transaction.put("donationWalletId", donationWalletId);
        transaction.put("walletId", walletId);
        transaction.put("amount", request.getAmount());
        transaction.put("idempotencyKey", idempotencyKey);
        donationMapper.insertWalletTransaction(transaction);

        Map<String, Object> updatedPot = donationMapper.findPotByMemberId(memberId);
        return DonationBalanceResponse.builder()
                .balance(decimal(updatedPot, "balance"))
                .build();
    }

    @Override
    @Transactional
    public DonationSettingsResponse saveSettings(String memberId, DonationSettingRequest request) {
        memberId = requireMemberId(memberId);
        if (SAVING_UNITS.stream().noneMatch(unit -> unit.compareTo(request.getSavingUnit()) == 0)) {
            throw new BusinessException("저금 단위는 10원, 100원, 1,000원 중 선택해 주세요.");
        }
        getOrCreateSettings(memberId);

        String campaignId = request.isAutoDonate() ? request.getCampaignId() : null;
        String organizationId = null;
        if (request.isAutoDonate()) {
            if (campaignId == null || campaignId.isBlank()) {
                throw new BusinessException("자동 기부 캠페인을 선택해 주세요.");
            }
            Map<String, Object> campaign = donationMapper.findCampaignById(campaignId);
            if (campaign == null) throw BusinessException.notFound("진행 중인 자동 기부 캠페인을 찾을 수 없습니다.");
            organizationId = text(campaign, "organization_id", "organizationId");
        }

        Map<String, Object> settings = new HashMap<>();
        settings.put("memberId", memberId);
        settings.put("piggyBankEnabled", request.isPiggyBankEnabled());
        settings.put("savingUnit", request.getSavingUnit());
        settings.put("autoDonate", request.isAutoDonate());
        settings.put("organizationId", organizationId);
        settings.put("campaignId", campaignId);
        if (donationMapper.updateSettings(settings) != 1) {
            throw BusinessException.conflict("저금통 설정을 저장하지 못했습니다.");
        }
        return toSettingsResponse(donationMapper.findSettings(memberId));
    }

    @Override
    public List<DonationHistoryResponse> getHistory(String memberId) {
        memberId = requireMemberId(memberId);
        Map<String, Object> pot = donationMapper.findPotByMemberId(memberId);
        if (pot == null) return Collections.emptyList();
        return donationMapper.findHistoryByWalletId(text(pot, "wallet_id", "walletId")).stream()
                .map(this::toHistoryResponse)
                .collect(Collectors.toList());
    }

    private DonationHistoryResponse toHistoryResponse(Map<String, Object> row) {
        // 기부 시점의 표시명 스냅샷을 우선 쓰고, 없으면 조인해온 현재 단체명으로 대체한다
        String organization = text(row, "recipient_name", "recipientName");
        if (organization == null) {
            organization = text(row, "organization_name", "organizationName");
        }
        return DonationHistoryResponse.builder()
                .donationId(text(row, "donation_id", "donationId"))
                .organization(organization)
                .campaignTitle(text(row, "campaign_title", "campaignTitle"))
                .amount(decimal(row, "amount"))
                .status(text(row, "status"))
                .receiptUrl(text(row, "receipt_url", "receiptUrl"))
                .completedAt(dateTimeText(value(row, "completed_at", "completedAt")))
                .createdAt(dateTimeText(value(row, "created_at", "createdAt")))
                .build();
    }

    @Override
    @Transactional
    public int processDailyRoundUps() {
        int completedCount = 0;
        for (Map<String, Object> candidate : donationMapper.findTodayRoundUpCandidates()) {
            BigDecimal paymentAmount = decimal(candidate, "amount");
            BigDecimal savingUnit = decimal(candidate, "savingUnit", "saving_unit");
            if (paymentAmount.signum() < 0 || savingUnit.signum() <= 0) continue;

            BigDecimal roundUpAmount = paymentAmount.remainder(savingUnit);
            Map<String, Object> pot = getOrCreatePotForUpdate(text(candidate, "memberId", "member_id"));
            String walletId = text(pot, "wallet_id", "walletId");
            Map<String, Object> roundUp = new HashMap<>();
            roundUp.put("sourceTxnId", text(candidate, "txnId", "txn_id"));
            roundUp.put("walletId", walletId);
            roundUp.put("savingUnit", savingUnit);
            roundUp.put("roundupAmount", roundUpAmount);
            roundUp.put("status", roundUpAmount.signum() == 0 ? "SKIPPED" : "PENDING");

            if (donationMapper.insertRoundUp(roundUp) != 1 || roundUpAmount.signum() == 0) continue;
            // roundup_id는 AUTO_INCREMENT — useGeneratedKeys가 파라미터 맵에 채워준다
            if (donationMapper.increasePotBalance(walletId, roundUpAmount) != 1
                    || donationMapper.completeRoundUp(text(roundUp, "roundupId")) != 1) {
                throw BusinessException.conflict("잔돈 적립을 반영하지 못했습니다.");
            }
            completedCount++;
        }
        return completedCount;
    }

    @Override
    @Transactional
    public int processMonthlyAutoDonations(String yearMonth) {
        if (yearMonth == null || !yearMonth.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw new BusinessException("자동 기부 기준 월 형식이 올바르지 않습니다.");
        }
        int completedCount = 0;
        for (Map<String, Object> candidate : donationMapper.findMonthlyAutoDonationCandidates(yearMonth)) {
            String memberId = text(candidate, "memberId", "member_id");
            String campaignId = text(candidate, "campaignId", "campaign_id");
            String idempotencyKey = "auto-" + memberId + "-" + yearMonth;
            Map<String, Object> existing = donationMapper.findHistoryByIdempotencyKey(memberId, idempotencyKey);
            if (existing == null) {
                Map<String, Object> campaign = donationMapper.findCampaignById(campaignId);
                if (campaign == null) continue;
                Map<String, Object> pot = getOrCreatePotForUpdate(memberId);
                BigDecimal amount = decimal(pot, "balance");
                if (amount.signum() <= 0) continue;
                donateToCampaign(memberId, campaign, amount, idempotencyKey);
                completedCount++;
            }
            if (donationMapper.markAutoDonationCompleted(memberId, yearMonth) != 1) {
                throw BusinessException.conflict("월말 자동 기부 상태를 반영하지 못했습니다.");
            }
        }
        return completedCount;
    }

    private Map<String, Object> getOrCreatePot(String memberId) {
        Map<String, Object> pot = donationMapper.findPotByMemberId(memberId);
        if (pot != null) return pot;
        Map<String, Object> created = new HashMap<>();
        created.put("memberId", memberId);
        created.put("balance", BigDecimal.ZERO);
        donationMapper.insertPot(created);
        return donationMapper.findPotByMemberId(memberId);
    }

    private Map<String, Object> getOrCreatePotForUpdate(String memberId) {
        getOrCreatePot(memberId);
        Map<String, Object> pot = donationMapper.findPotForUpdate(memberId);
        if (pot == null) throw BusinessException.notFound("저금통을 찾을 수 없습니다.");
        return pot;
    }

    private Map<String, Object> getOrCreateSettings(String memberId) {
        Map<String, Object> settings = donationMapper.findSettings(memberId);
        if (settings != null) return settings;
        donationMapper.insertSettings(memberId);
        return donationMapper.findSettings(memberId);
    }

    private DonationCampaignResponse toCampaignResponse(Map<String, Object> row) {
        BigDecimal target = decimal(row, "targetAmount", "target_amount");
        BigDecimal raised = decimal(row, "raised");
        int progress = target.signum() <= 0 ? 0 : raised.multiply(BigDecimal.valueOf(100))
                .divide(target, 0, RoundingMode.DOWN).intValue();
        progress = Math.max(0, Math.min(progress, 100));
        LocalDateTime endsAt = localDateTime(value(row, "endsAt", "ends_at"));
        long daysLeft = endsAt == null ? 0 : Math.max(ChronoUnit.DAYS.between(LocalDateTime.now(), endsAt) + 1, 0);
        return DonationCampaignResponse.builder()
                .id(text(row, "id"))
                .organization(text(row, "organization"))
                .title(text(row, "title"))
                .category(text(row, "category"))
                .progress(progress)
                .raised(raised)
                .participants(number(row, "participants"))
                .daysLeft(daysLeft)
                .preferred(bool(row, "preferred"))
                .build();
    }

    private DonationSettingsResponse toSettingsResponse(Map<String, Object> settings) {
        return DonationSettingsResponse.builder()
                .piggyBankEnabled(bool(settings, "piggyBankEnabled", "piggy_bank_enabled"))
                .savingUnit(decimal(settings, "savingUnit", "saving_unit"))
                .autoDonate(bool(settings, "autoDonate", "auto_donate_enabled"))
                .autoDonateCampaignId(text(settings, "autoDonateCampaignId", "auto_donate_campaign_id"))
                .build();
    }

    private String buildImpactMessage(BigDecimal balance) {
        if (balance.signum() <= 0) return "첫 잔돈을 모아 반려동물을 위한 변화를 시작해 보세요";
        int meals = Math.max(balance.divide(BigDecimal.valueOf(4000), 0, RoundingMode.DOWN).intValue(), 1);
        return "유기동물 " + meals + "마리의 한 끼를 도울 수 있어요";
    }

    private String requireMemberId(String memberId) {
        if (memberId == null || memberId.isBlank()) throw BusinessException.unauthorized("로그인이 필요합니다.");
        return memberId;
    }

    private String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException("중복 요청 방지 키를 입력해 주세요.");
        }
        if (idempotencyKey.length() > 64) {
            throw new BusinessException("중복 요청 방지 키는 64자 이하여야 합니다.");
        }
        return idempotencyKey;
    }

    private static Object value(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map != null && map.containsKey(key)) return map.get(key);
        return null;
    }

    private static String text(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal decimal(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        if (value == null) return BigDecimal.ZERO;
        return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(String.valueOf(value));
    }

    private static int number(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        return value instanceof Number ? ((Number) value).intValue() : value == null ? 0 : Integer.parseInt(String.valueOf(value));
    }

    private static boolean bool(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        return value instanceof Boolean ? (Boolean) value
                : value instanceof Number ? ((Number) value).intValue() == 1
                : "Y".equalsIgnoreCase(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String dateTimeText(Object value) {
        LocalDateTime dateTime = localDateTime(value);
        return dateTime == null ? null : dateTime.toString();
    }

    private static LocalDateTime localDateTime(Object value) {
        if (value instanceof LocalDateTime) return (LocalDateTime) value;
        if (value instanceof Timestamp) return ((Timestamp) value).toLocalDateTime();
        return null;
    }
}
