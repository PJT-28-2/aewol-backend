package com.aewol.domain.insight.service.collector;

import com.aewol.domain.dashboard.service.DashboardService;
import com.aewol.domain.insight.service.InsightCard;
import com.aewol.domain.insight.service.InsightCardCollector;
import com.aewol.domain.insight.service.InsightCardType;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private final DashboardService dashboardService;

    @Override
    public InsightCardType type() {
        return InsightCardType.SPENDING;
    }

    @Override
    public InsightCard collect(String memberId, String petId) {
        String month = LocalDate.now().format(MONTH);
        Map<String, Object> summary;
        Map<String, Object> breakdown;
        try {
            summary = dashboardService.getMonthlySummary(memberId, month);
            breakdown = dashboardService.getCategoryBreakdown(memberId, "CATEGORY", month);
        } catch (RuntimeException e) {
            log.debug("[Insight] 지출 조회 실패 - memberId: {}, reason: {}", memberId, e.getMessage());
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

        List<Map<String, Object>> items = items(breakdown.get("items"));
        String facts = items.stream()
                .sorted((a, b) -> amount(b.get("amount")).compareTo(amount(a.get("amount"))))
                .limit(5)
                .map(item -> "- %s %s원".formatted(
                        categoryLabel(String.valueOf(item.get("category"))),
                        WON.format(amount(item.get("amount")))))
                .collect(Collectors.joining("\n"));

        String top = items.stream()
                .max((a, b) -> amount(a.get("amount")).compareTo(amount(b.get("amount"))))
                .map(item -> categoryLabel(String.valueOf(item.get("category"))))
                .orElse("지출");

        return InsightCard.builder()
                .type(type())
                .headline("이번 달 지출 %s원".formatted(WON.format(total)))
                .facts("""
                        기간: %s
                        총 지출: %s원 %s
                        카테고리별:
                        %s"""
                        .formatted(month, WON.format(total), changeText, facts))
                .fallbackBody("이번 달에 %s원을 썼고 %s 비중이 가장 큽니다. 내역에서 항목별로 확인해 보세요."
                        .formatted(WON.format(total), top))
                .ctaLabel("내역 보기")
                .ctaPath("/wallet")
                .digest(month + ":" + total.toPlainString())
                .build();
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
