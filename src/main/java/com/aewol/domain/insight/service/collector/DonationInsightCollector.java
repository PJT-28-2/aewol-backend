package com.aewol.domain.insight.service.collector;

import com.aewol.domain.donation.dto.DonationCampaignResponse;
import com.aewol.domain.donation.dto.DonationOverviewResponse;
import com.aewol.domain.donation.service.DonationService;
import com.aewol.domain.insight.service.InsightCard;
import com.aewol.domain.insight.service.InsightCardCollector;
import com.aewol.domain.insight.service.InsightCardType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 짜투리지갑에 모인 잔돈과 기부할 곳을 안내한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DonationInsightCollector implements InsightCardCollector {

    private static final NumberFormat WON = NumberFormat.getIntegerInstance(Locale.KOREA);
    private static final int PROMPT_LIMIT = 3;

    private final DonationService donationService;

    @Override
    public InsightCardType type() {
        return InsightCardType.DONATION;
    }

    @Override
    public InsightCard collect(String memberId, String petId) {
        DonationOverviewResponse overview;
        try {
            overview = donationService.getOverview(memberId);
        } catch (RuntimeException e) {
            log.debug("[Insight] 짜투리지갑 조회 실패 - cause: {}", e.getClass().getSimpleName());
            return null;
        }
        if (overview == null) {
            return null;
        }

        BigDecimal balance = overview.getBalance() == null ? BigDecimal.ZERO : overview.getBalance();
        // 한 푼도 안 모였으면 안내할 것이 없다.
        if (balance.signum() <= 0) {
            return null;
        }
        BigDecimal monthly = overview.getMonthlySaved() == null ? BigDecimal.ZERO : overview.getMonthlySaved();

        List<DonationCampaignResponse> campaigns =
                overview.getCampaigns() == null ? List.of() : overview.getCampaigns();
        String campaignFacts = campaigns.stream()
                .limit(PROMPT_LIMIT)
                .map(campaign -> "- %s".formatted(campaign.getTitle()))
                .collect(Collectors.joining("\n"));

        // 이번 달 적립분만으로 속도를 잰다. 누적액에는 지난달까지의 적립이 섞여 있어
        // 그것으로 나누면 지금 페이스가 아니라 평생 평균이 나온다.
        LocalDate today = LocalDate.now();
        BigDecimal projectedMonthly = MonthPace.projectMonthEnd(monthly, today);
        String projection = projectedMonthly == null ? null
                : "이 속도면 이달 말 누적 약 %s원 (이번 달 %s원 예상)".formatted(
                        WON.format(balance.subtract(monthly).add(projectedMonthly)),
                        WON.format(projectedMonthly));

        return InsightCard.builder()
                .type(type())
                .headline("모인 잔돈 %s원".formatted(WON.format(balance)))
                .projection(projection)
                .facts("""
                        짜투리지갑(결제 잔돈 자동 적립) 현황
                        - 누적 %s원, 이번 달 %s원 적립
                        - %s
                        기부할 수 있는 캠페인:
                        %s"""
                        .formatted(WON.format(balance), WON.format(monthly),
                                overview.getImpactMessage() == null ? "적립 중" : overview.getImpactMessage(),
                                campaignFacts.isBlank() ? "- (진행 중인 캠페인 없음)" : campaignFacts))
                .fallbackBody("짜투리지갑에 %s원이 모였고 이번 달에 %s원이 더해졌어요. %s"
                        .formatted(WON.format(balance), WON.format(monthly),
                                overview.getImpactMessage() == null
                                        ? "기부할 곳을 골라보세요." : overview.getImpactMessage()))
                .ctaLabel("기부하기")
                .ctaPath("/donation")
                // 남은 일수가 줄면 예측 문장이 달라지므로 날짜를 지문에 넣는다.
                .digest(today + ":" + balance.toPlainString() + ":" + campaigns.size())
                .build();
    }
}
