package com.aewol.domain.insight.service.collector;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.aewol.domain.insight.service.InsightCard;
import com.aewol.domain.share.dto.ShareActivityResponse;
import com.aewol.domain.share.dto.ShareContributionResponse;
import com.aewol.domain.share.dto.SharePetResponse;
import com.aewol.domain.share.service.ShareService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CareInsightCollectorTest {

    @Mock ShareService shareService;
    @InjectMocks CareInsightCollector collector;

    /**
     * 오늘로부터 며칠 전에 기록이 몇 건 있었는지로 로그를 만든다.
     *
     * <p>날짜를 고정하지 않는 것은 의도된 것이다. 수집기가 {@code LocalDate.now()}를
     * 쓰기 때문에 테스트도 오늘을 기준으로 삼아야 한다. 대신 경계에 걸치지 않도록
     * 주 한가운데 날짜만 쓴다.
     */
    private List<ShareActivityResponse> logs(int daysAgo, int count) {
        List<ShareActivityResponse> logs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            logs.add(ShareActivityResponse.builder()
                    .id(daysAgo + "-" + i)
                    .title("산책 기록")
                    .createdAt(LocalDateTime.of(LocalDate.now().minusDays(daysAgo), java.time.LocalTime.NOON)
                            .toString())
                    .build());
        }
        return logs;
    }

    private void given(List<ShareActivityResponse> logs) {
        when(shareService.getAccessiblePets("m1")).thenReturn(List.of(
                SharePetResponse.builder().id("p1").name("보리").build()));
        when(shareService.getContributions("m1", "p1")).thenReturn(List.of(
                ShareContributionResponse.builder().id("m1").name("김애월").percentage(60).build(),
                ShareContributionResponse.builder().id("m2").name("이지원").percentage(40).build()));
        when(shareService.getLogs("m1", "p1")).thenReturn(logs);
    }

    @Test
    @DisplayName("최근 한 주가 그 전 주보다 많으면 늘었다고 말한다")
    void should_reportIncrease_when_thisWeekHasMoreLogs() {
        List<ShareActivityResponse> logs = new ArrayList<>(logs(3, 5));
        logs.addAll(logs(10, 2));
        given(logs);

        InsightCard card = collector.collect("m1", null);

        assertNotNull(card);
        assertTrue(card.getProjection().contains("최근 7일 5건"), card.getProjection());
        assertTrue(card.getProjection().contains("3건 늘었습니다"), card.getProjection());
    }

    @Test
    @DisplayName("오늘 포함 7일과 그 전 7일을 경계값대로 나눈다")
    void should_countExactlySevenDays_atWeekBoundaries() {
        List<ShareActivityResponse> logs = new ArrayList<>();
        logs.addAll(logs(0, 1));
        logs.addAll(logs(6, 1));
        logs.addAll(logs(7, 1));
        logs.addAll(logs(13, 1));
        logs.addAll(logs(14, 5));
        given(logs);

        InsightCard card = collector.collect("m1", null);

        assertNotNull(card);
        assertTrue(card.getProjection().contains("최근 7일 2건"), card.getProjection());
        assertTrue(card.getProjection().contains("같은 속도입니다"), card.getProjection());
    }

    @Test
    @DisplayName("그 전 주에 기록이 없으면 증감 대신 건수만 말한다")
    void should_reportCountOnly_when_previousWeekEmpty() {
        given(new ArrayList<>(logs(2, 4)));

        InsightCard card = collector.collect("m1", null);

        assertNotNull(card);
        assertTrue(card.getProjection().contains("그 전 주에는 기록이 없었습니다"), card.getProjection());
    }

    // 두 주 모두 비어 있으면 추세라 부를 것이 없다. 예측 줄을 빼야 모델도
    // 앞날에 대해 말하지 않는다.
    @Test
    @DisplayName("최근 두 주가 모두 비어 있으면 예측하지 않는다")
    void should_omitProjection_when_bothWeeksEmpty() {
        given(new ArrayList<>(logs(40, 3)));

        InsightCard card = collector.collect("m1", null);

        assertNotNull(card);
        assertNull(card.getProjection());
    }

    @Test
    @DisplayName("createdAt 형식이 어긋난 기록은 집계에서 빼고 카드는 살린다")
    void should_ignoreUnparsableDates_when_counting() {
        List<ShareActivityResponse> logs = new ArrayList<>(logs(2, 1));
        logs.add(ShareActivityResponse.builder().id("x").title("깨진 기록").createdAt("어제").build());
        logs.add(ShareActivityResponse.builder().id("y").title("빈 기록").createdAt(null).build());
        given(logs);

        InsightCard card = collector.collect("m1", null);

        assertNotNull(card);
        assertTrue(card.getProjection().contains("최근 7일에 1건"), card.getProjection());
    }
}
