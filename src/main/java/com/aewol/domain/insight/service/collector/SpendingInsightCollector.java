package com.aewol.domain.insight.service.collector;

import com.aewol.common.util.Sha256Util;
import com.aewol.domain.dashboard.service.DashboardService;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseCategory;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListItemResponse;
import com.aewol.domain.grouppurchase.service.GroupPurchaseService;
import com.aewol.domain.grouppurchase.service.GroupPurchaseStatus;
import com.aewol.domain.insight.dto.CategoryShare;
import com.aewol.domain.insight.service.InsightCard;
import com.aewol.domain.insight.service.InsightCardCollector;
import com.aewol.domain.insight.service.InsightCardType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 이번 달 지출을 카테고리별로 짚어준다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpendingInsightCollector implements InsightCardCollector {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final NumberFormat WON = NumberFormat.getIntegerInstance(Locale.KOREA);
    /** 공동구매에 실제로 등록되는 카테고리만 추천 대상으로 삼는다. 의료/보험처럼 상품이 없는 카테고리는 추천하지 않는다. */
    private static final Set<String> RECOMMENDABLE_CATEGORIES =
            Set.of(GroupPurchaseCategory.FOOD, GroupPurchaseCategory.SNACK, GroupPurchaseCategory.SUPPLY);
    private static final int RECOMMENDATION_LIMIT = 3;
    /** categoryLabel()이 INSURANCE에 대해 돌려주는 라벨과 반드시 같아야 한다. */
    private static final String INSURANCE_LABEL = "보험";
    /** 도넛 차트 조각이 너무 많아지면 오히려 안 읽힌다. 이보다 작은 항목은 '기타'로 합친다. */
    private static final int DONUT_MAX_SLICES = 4;
    private static final String OTHER_LABEL = "기타";

    private final DashboardService dashboardService;
    private final GroupPurchaseService groupPurchaseService;

    @Override
    public InsightCardType type() {
        return InsightCardType.SPENDING;
    }

    @Override
    public InsightCard collect(String memberId, String petId) {
        LocalDate today = LocalDate.now();
        String month = today.format(MONTH);
        Map<String, Object> summary;
        Map<String, Object> breakdown;
        try {
            summary = dashboardService.getMonthlySummary(memberId, month);
            breakdown = dashboardService.getCategoryBreakdown(memberId, "CATEGORY", month);
        } catch (RuntimeException e) {
            log.debug("[Insight] 지출 조회 실패 - cause: {}", e.getClass().getSimpleName());
            return null;
        }
        if (summary == null || breakdown == null) {
            return null;
        }

        BigDecimal total = amount(breakdown.get("totalAmount"));
        // 이번 달 지출이 없으면 할 말이 없다. 억지로 카드를 띄우지 않는다.
        if (total.signum() <= 0) {
            return null;
        }

        Object monthlySpend = summary.get("monthlySpend");
        String changeText = "";
        if (monthlySpend instanceof Map<?, ?> spend && spend.get("changeRate") != null) {
            BigDecimal rate = amount(spend.get("changeRate"));
            if (rate.signum() != 0) {
                changeText = "전월 대비 %s%s%%".formatted(rate.signum() > 0 ? "+" : "", rate.stripTrailingZeros().toPlainString());
            }
        }

        // getCategoryBreakdown은 카테고리+반려동물 조합으로 행을 나눠 준다. 반려동물이
        // 여럿이면 같은 카테고리가 여러 행으로 쪼개져 있으므로, 라벨 기준으로 먼저 합산해야
        // 1위 판정이 맞는다 — 합산하지 않으면 본문 문장(topLabel)과 도넛 차트가 서로
        // 다른 카테고리를 1위라고 말하는 사고가 날 수 있다.
        List<Map<String, Object>> items = items(breakdown.get("items"));
        Map<String, BigDecimal> amountByLabel = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            amountByLabel.merge(
                    categoryLabel(String.valueOf(item.get("category"))),
                    amount(item.get("amount")),
                    BigDecimal::add);
        }
        List<Map.Entry<String, BigDecimal>> sortedByLabel = amountByLabel.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .toList();

        String facts = sortedByLabel.stream()
                .limit(5)
                .map(entry -> "- %s %s원".formatted(entry.getKey(), WON.format(entry.getValue())))
                .collect(Collectors.joining("\n"));

        String topLabel = sortedByLabel.isEmpty() ? "지출" : sortedByLabel.get(0).getKey();
        BigDecimal topAmount = sortedByLabel.isEmpty() ? BigDecimal.ZERO : sortedByLabel.get(0).getValue();
        // 정수 %로 충분하다. 소수점까지 보여주면 "분석"이 아니라 "계산기"처럼 읽힌다.
        int topPercentage = percentageOf(topAmount, total);
        List<CategoryShare> categoryBreakdown = buildCategoryBreakdown(sortedByLabel, total);

        BigDecimal projected = MonthPace.projectMonthEnd(total, today);
        // 숫자로 뚝 끊기지 않고 말로 끝나야 카드의 다른 문장들("~예요", "~해 보세요")과
        // 톤이 맞는다. 남은 일수는 헤드라인·본문에 없는 새 정보가 아니라 부가 정보라 뺐다.
        String projection = projected == null ? null
                : "이 속도면 이달 말까지 약 %s원을 쓸 것 같아요".formatted(WON.format(projected));

        // 보험이 1위면 공동구매로 보낼 상품이 없다(카테고리 자체가 없음). 대신 보험료를
        // 직접 줄여볼 수 있는 시뮬레이터로 안내한다 — '내역 보기'보다 실제로 도움이 된다.
        boolean isInsuranceTop = INSURANCE_LABEL.equals(topLabel);
        String ctaLabel = isInsuranceTop ? "보험료 시뮬레이션 하러 가기" : "내역 보기";
        String ctaPath = isInsuranceTop ? "/insurance/simulator" : "/wallet/history";
        String closingHint = isInsuranceTop
                ? "보험료가 부담된다면 시뮬레이터로 비교해 보세요."
                : "내역에서 항목별로 확인해 보세요.";

        // 총액은 헤드라인에 이미 있다. 본문까지 그대로 반복하면 정보가 없는 문장이 된다.
        // 대신 비중(%)과 전월 대비 증감처럼 비교가 담긴 사실을 우선 채운다.
        String fallbackBody = changeText.isEmpty()
                ? "%s가 %d%%(%s원)로 가장 큰 비중을 차지해요. %s"
                        .formatted(topLabel, topPercentage, WON.format(topAmount), closingHint)
                : "%s가 %d%%(%s원)로 가장 크고, %s예요. %s"
                        .formatted(topLabel, topPercentage, WON.format(topAmount), changeText, closingHint);

        return InsightCard.builder()
                .type(type())
                .headline("이번 달 지출 %s원".formatted(WON.format(total)))
                .projection(projection)
                .facts("""
                        기간: %s
                        총 지출: %s원 %s
                        가장 비중이 큰 카테고리: %s (%d%%, %s원)
                        카테고리별:
                        %s"""
                        .formatted(month, WON.format(total), changeText, topLabel, topPercentage, WON.format(topAmount), facts))
                .fallbackBody(fallbackBody)
                .ctaLabel(ctaLabel)
                .ctaPath(ctaPath)
                // 남은 일수가 매일 줄어 예측 문장도 매일 달라진다. 날짜를 지문에 넣어
                // 어제 문장이 오늘 그대로 재사용되지 않게 한다.
                .digest(Sha256Util.lowercaseHex(
                        today + "\n" + total.toPlainString() + "\n" + changeText + "\n" + facts))
                .recommendedProducts(recommendProducts(memberId, topLabel))
                .categoryBreakdown(categoryBreakdown)
                .build();
    }

    /** 상위 {@link #DONUT_MAX_SLICES}개만 조각으로 남기고 나머지는 '기타'로 합친다. */
    private static List<CategoryShare> buildCategoryBreakdown(
            List<Map.Entry<String, BigDecimal>> sortedByLabel, BigDecimal total) {
        List<CategoryShare> shares = new ArrayList<>();
        BigDecimal otherAmount = BigDecimal.ZERO;
        for (int i = 0; i < sortedByLabel.size(); i++) {
            Map.Entry<String, BigDecimal> entry = sortedByLabel.get(i);
            if (i >= DONUT_MAX_SLICES || OTHER_LABEL.equals(entry.getKey())) {
                otherAmount = otherAmount.add(entry.getValue());
            } else {
                shares.add(toShare(entry.getKey(), entry.getValue(), total));
            }
        }
        if (otherAmount.signum() > 0) {
            shares.add(toShare(OTHER_LABEL, otherAmount, total));
        }
        return normalizePercentages(shares);
    }

    private static List<CategoryShare> normalizePercentages(List<CategoryShare> shares) {
        if (shares.isEmpty()) {
            return shares;
        }
        int adjustment = 100 - shares.stream().mapToInt(CategoryShare::getPercentage).sum();
        if (adjustment == 0) {
            return shares;
        }

        List<CategoryShare> normalized = new ArrayList<>(shares);
        int lastIndex = normalized.size() - 1;
        CategoryShare last = normalized.get(lastIndex);
        normalized.set(lastIndex, CategoryShare.builder()
                .label(last.getLabel())
                .amount(last.getAmount())
                .percentage(last.getPercentage() + adjustment)
                .build());
        return normalized;
    }

    private static CategoryShare toShare(String label, BigDecimal amount, BigDecimal total) {
        return CategoryShare.builder()
                .label(label)
                .amount(amount)
                .percentage(percentageOf(amount, total))
                .build();
    }

    private static int percentageOf(BigDecimal amount, BigDecimal total) {
        return amount.signum() <= 0
                ? 0
                : amount.multiply(BigDecimal.valueOf(100)).divide(total, 0, RoundingMode.HALF_UP).intValue();
    }

    /**
     * 가장 많이 쓴 카테고리로 진행 중인 공동구매를 추천한다. 의료·보험처럼 공동구매
     * 카테고리에 없는 지출은 추천할 상품이 없으므로 빈 리스트를 돌려준다.
     * 마감이 임박한 것부터 보여준다 — 최신 등록순으로 주면 곧 끝나는 상품을 놓칠 수 있다.
     */
    private List<GroupPurchaseListItemResponse> recommendProducts(String memberId, String topCategoryLabel) {
        if (!RECOMMENDABLE_CATEGORIES.contains(topCategoryLabel)) {
            return List.of();
        }
        try {
            return groupPurchaseService
                    .list(memberId, GroupPurchaseStatus.OPEN, null, topCategoryLabel, null, RECOMMENDATION_LIMIT, "DEADLINE_ASC")
                    .getItems();
        } catch (RuntimeException e) {
            log.debug("[Insight] 공동구매 추천 조회 실패 - cause: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** 화면과 프롬프트에서 쓰는 한글 라벨. 코드값을 그대로 노출하면 읽히지 않는다. */
    private static String categoryLabel(String category) {
        return switch (category == null ? "" : category) {
            case "HOSPITAL" -> "의료";
            case "FOOD" -> "사료";
            case "SNACK" -> "간식";
            case "GROOMING" -> "미용";
            case "INSURANCE" -> "보험";
            case "SUPPLIES" -> "용품";
            case "DONATION" -> "기부";
            default -> "기타";
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Object raw) {
        return raw instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static BigDecimal amount(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return value instanceof Number number ? BigDecimal.valueOf(number.doubleValue()) : BigDecimal.ZERO;
    }
}
