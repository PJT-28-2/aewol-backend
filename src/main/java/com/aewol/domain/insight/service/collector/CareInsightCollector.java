package com.aewol.domain.insight.service.collector;

import com.aewol.domain.insight.service.InsightCard;
import com.aewol.domain.insight.service.InsightCardCollector;
import com.aewol.domain.insight.service.InsightCardType;
import com.aewol.domain.share.dto.ShareActivityResponse;
import com.aewol.domain.share.dto.ShareContributionResponse;
import com.aewol.domain.share.dto.SharePetResponse;
import com.aewol.domain.share.service.ShareService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 공동육아 참여 기록을 요약한다.
 *
 * <p>혼자 키우는 회원에게는 보여줄 것이 없으므로 구성원이 2명 이상일 때만 만든다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CareInsightCollector implements InsightCardCollector {

    private static final int PROMPT_LIMIT = 8;

    private final ShareService shareService;

    @Override
    public InsightCardType type() {
        return InsightCardType.CARE;
    }

    @Override
    public InsightCard collect(String memberId, String petId) {
        try {
            List<SharePetResponse> pets = shareService.getAccessiblePets(memberId);
            if (pets == null || pets.isEmpty()) {
                return null;
            }
            final String requestedPetId = petId;
            SharePetResponse pet = pets.stream()
                    .filter(candidate -> requestedPetId == null || requestedPetId.equals(candidate.getId()))
                    .findFirst()
                    .orElse(pets.get(0));
            String targetPetId = pet.getId();
            String petName = pet.getName();

            List<ShareContributionResponse> contributions = shareService.getContributions(memberId, targetPetId);
            // 혼자 키우면 '함께한 기록'이 성립하지 않는다.
            if (contributions == null || contributions.size() < 2) {
                return null;
            }
            List<ShareActivityResponse> logs = shareService.getLogs(memberId, targetPetId);
            if (logs == null || logs.isEmpty()) {
                return null;
            }

            String people = contributions.stream()
                    .map(c -> "- %s: 지출 비중 %d%%".formatted(c.getName(), c.getPercentage()))
                    .collect(Collectors.joining("\n"));
            String recent = logs.stream()
                    .limit(PROMPT_LIMIT)
                    .map(log -> "- %s".formatted(log.getTitle()))
                    .collect(Collectors.joining("\n"));

            ShareContributionResponse topContributor = contributions.stream()
                    .max((a, b) -> Integer.compare(a.getPercentage(), b.getPercentage()))
                    .orElse(contributions.get(0));

            LocalDate today = LocalDate.now();
            long thisWeek = countSince(logs, today.minusDays(7));
            long lastWeek = countBetween(logs, today.minusDays(14), today.minusDays(7));
            String projection = weeklyTrend(thisWeek, lastWeek);

            return InsightCard.builder()
                    .type(type())
                    .headline("%s와 함께한 기록 %d건".formatted(petName, logs.size()))
                    .projection(projection)
                    .facts("""
                            반려동물: %s
                            공동육아 구성원 %d명:
                            %s
                            최근 활동 %d건 중 일부:
                            %s"""
                            .formatted(petName, contributions.size(), people, logs.size(), recent))
                    .fallbackBody("%s를 %d명이 함께 돌보고 있고 기록이 %d건 쌓였어요. %s님 참여가 가장 많습니다."
                            .formatted(petName, contributions.size(), logs.size(), topContributor.getName()))
                    .ctaLabel("활동 보기")
                    .ctaPath("/share")
                    .digest(today + ":" + targetPetId + ":" + logs.size())
                    .build();
        } catch (RuntimeException e) {
            log.debug("[Insight] 공동육아 조회 실패 - memberId: {}, reason: {}", memberId, e.getMessage());
            return null;
        }
    }

    /**
     * 최근 한 주와 그 전 한 주를 견줘 참여가 늘고 있는지 말한다.
     *
     * <p>둘 다 0이면 비교할 것이 없다. 지난주가 0인데 이번 주에 기록이 생긴 경우는
     * 증가율을 낼 수 없으므로 건수만 말한다. 0으로 나누느니 덜 말하는 편이 낫다.
     */
    private static String weeklyTrend(long thisWeek, long lastWeek) {
        if (thisWeek == 0 && lastWeek == 0) {
            return null;
        }
        if (lastWeek == 0) {
            return "최근 7일에 %d건 — 그 전 주에는 기록이 없었습니다".formatted(thisWeek);
        }
        long diff = thisWeek - lastWeek;
        if (diff == 0) {
            return "최근 7일 %d건으로 그 전 주와 같은 속도입니다".formatted(thisWeek);
        }
        return "최근 7일 %d건 — 그 전 주보다 %d건 %s".formatted(
                thisWeek, Math.abs(diff), diff > 0 ? "늘었습니다" : "줄었습니다");
    }

    private static long countSince(List<ShareActivityResponse> logs, LocalDate from) {
        return logs.stream().filter(entry -> isOnOrAfter(entry, from)).count();
    }

    private static long countBetween(List<ShareActivityResponse> logs, LocalDate from, LocalDate toExclusive) {
        return logs.stream()
                .filter(entry -> isOnOrAfter(entry, from))
                .filter(entry -> !isOnOrAfter(entry, toExclusive))
                .count();
    }

    /** createdAt 이 비었거나 형식이 어긋나면 집계에서 뺀다. 파싱 실패로 카드를 버릴 일은 아니다. */
    private static boolean isOnOrAfter(ShareActivityResponse entry, LocalDate from) {
        String createdAt = entry.getCreatedAt();
        if (createdAt == null || createdAt.isBlank()) {
            return false;
        }
        try {
            return !LocalDateTime.parse(createdAt).toLocalDate().isBefore(from);
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
