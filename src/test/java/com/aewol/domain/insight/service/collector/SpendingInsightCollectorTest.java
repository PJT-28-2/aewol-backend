package com.aewol.domain.insight.service.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aewol.domain.dashboard.service.DashboardService;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListItemResponse;
import com.aewol.domain.grouppurchase.dto.GroupPurchaseListResponse;
import com.aewol.domain.grouppurchase.service.GroupPurchaseService;
import com.aewol.domain.grouppurchase.service.GroupPurchaseStatus;
import com.aewol.domain.insight.service.InsightCard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
class SpendingInsightCollectorTest {

    @Mock DashboardService dashboardService;
    @Mock GroupPurchaseService groupPurchaseService;
    @InjectMocks SpendingInsightCollector collector;

    private void givenBreakdown(BigDecimal changeRate, List<Map<String, Object>> items, BigDecimal total) {
        when(dashboardService.getMonthlySummary(eq("m1"), any()))
                .thenReturn(Map.of("monthlySpend", changeRate == null
                        ? Map.of()
                        : Map.of("changeRate", changeRate)));
        when(dashboardService.getCategoryBreakdown(eq("m1"), eq("CATEGORY"), any()))
                .thenReturn(Map.of("totalAmount", total, "items", items));
    }

    @Test
    @DisplayName("가장 비중이 큰 카테고리의 비율(%)과 전월 대비 증감을 문구에 담는다")
    void should_includePercentageAndChangeRate_inFallbackBody() {
        givenBreakdown(
                BigDecimal.valueOf(12),
                List.of(
                        Map.of("category", "FOOD", "amount", BigDecimal.valueOf(70_000)),
                        Map.of("category", "SNACK", "amount", BigDecimal.valueOf(30_000))),
                BigDecimal.valueOf(100_000));
        when(groupPurchaseService.list(eq("m1"), eq(GroupPurchaseStatus.OPEN), eq(null), eq("사료"), eq(0), anyInt(), any()))
                .thenReturn(GroupPurchaseListResponse.builder().items(List.of()).hasNext(false).build());

        InsightCard card = collector.collect("m1", "p1");

        assertNotNull(card);
        assertTrue(card.getFallbackBody().contains("사료가 70%"), card.getFallbackBody());
        assertTrue(card.getFallbackBody().contains("전월 대비 +12%"), card.getFallbackBody());
        // 헤드라인에 이미 총액이 있으니 본문에서 다시 반복하지 않는다.
        assertTrue(!card.getFallbackBody().contains("이번 달에 100,000원을 썼고"), card.getFallbackBody());
    }

    @Test
    @DisplayName("가장 많이 쓴 카테고리가 공동구매 카테고리와 맞으면 상품을 추천한다")
    void should_recommendProducts_when_topCategoryMatchesGroupPurchaseCategory() {
        givenBreakdown(
                null,
                List.of(Map.of("category", "FOOD", "amount", BigDecimal.valueOf(50_000))),
                BigDecimal.valueOf(50_000));
        GroupPurchaseListItemResponse product = GroupPurchaseListItemResponse.builder()
                .id(1L)
                .productName("사료 5kg")
                .category("사료")
                .build();
        when(groupPurchaseService.list("m1", GroupPurchaseStatus.OPEN, null, "사료", 0, 3, "DEADLINE_ASC"))
                .thenReturn(GroupPurchaseListResponse.builder().items(List.of(product)).hasNext(false).build());

        InsightCard card = collector.collect("m1", "p1");

        assertNotNull(card);
        assertEquals(1, card.getRecommendedProducts().size());
        assertEquals("사료 5kg", card.getRecommendedProducts().get(0).getProductName());
    }

    @Test
    @DisplayName("추천 조회는 최신 등록순이 아니라 마감임박순(DEADLINE_ASC)으로 요청한다")
    void should_requestDeadlineAscendingSort_whenFetchingRecommendations() {
        givenBreakdown(
                null,
                List.of(Map.of("category", "SUPPLIES", "amount", BigDecimal.valueOf(30_000))),
                BigDecimal.valueOf(30_000));
        when(groupPurchaseService.list(any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(GroupPurchaseListResponse.builder().items(List.of()).hasNext(false).build());

        collector.collect("m1", "p1");

        org.mockito.Mockito.verify(groupPurchaseService)
                .list("m1", GroupPurchaseStatus.OPEN, null, "용품", 0, 3, "DEADLINE_ASC");
    }

    @Test
    @DisplayName("가장 많이 쓴 카테고리가 의료처럼 공동구매에 없는 카테고리면 추천하지 않는다")
    void should_returnEmptyRecommendations_when_topCategoryHasNoGroupPurchaseMatch() {
        givenBreakdown(
                null,
                List.of(Map.of("category", "HOSPITAL", "amount", BigDecimal.valueOf(80_000))),
                BigDecimal.valueOf(80_000));

        InsightCard card = collector.collect("m1", "p1");

        assertNotNull(card);
        assertTrue(card.getRecommendedProducts().isEmpty());
        verifyNoInteractions(groupPurchaseService);
    }

    @Test
    @DisplayName("공동구매 조회가 실패해도 카드는 살리고 추천만 비운다")
    void should_returnEmptyRecommendations_when_groupPurchaseServiceThrows() {
        givenBreakdown(
                null,
                List.of(Map.of("category", "SNACK", "amount", BigDecimal.valueOf(20_000))),
                BigDecimal.valueOf(20_000));
        when(groupPurchaseService.list("m1", GroupPurchaseStatus.OPEN, null, "간식", 0, 3, "DEADLINE_ASC"))
                .thenThrow(new RuntimeException("boom"));

        InsightCard card = collector.collect("m1", "p1");

        assertNotNull(card);
        assertTrue(card.getRecommendedProducts().isEmpty());
    }

    @Test
    @DisplayName("가장 많이 쓴 카테고리가 보험이면 내역 대신 보험료 시뮬레이터로 안내한다")
    void should_pointCtaToInsuranceSimulator_when_topCategoryIsInsurance() {
        givenBreakdown(
                null,
                List.of(Map.of("category", "INSURANCE", "amount", BigDecimal.valueOf(45_000))),
                BigDecimal.valueOf(45_000));

        InsightCard card = collector.collect("m1", "p1");

        assertNotNull(card);
        assertEquals("/insurance/simulator", card.getCtaPath());
        assertEquals("보험료 시뮬레이션 하러 가기", card.getCtaLabel());
        assertTrue(card.getFallbackBody().contains("시뮬레이터로 비교해 보세요"), card.getFallbackBody());
        // 보험은 공동구매 카테고리가 아니므로 상품 추천은 비어 있어야 한다.
        assertTrue(card.getRecommendedProducts().isEmpty());
        verifyNoInteractions(groupPurchaseService);
    }

    @Test
    @DisplayName("도넛 차트용 카테고리 비중을 비중 내림차순으로 계산한다")
    void should_buildCategoryBreakdown_sortedByShareDescending() {
        givenBreakdown(
                null,
                List.of(
                        Map.of("category", "FOOD", "amount", BigDecimal.valueOf(70_000)),
                        Map.of("category", "SNACK", "amount", BigDecimal.valueOf(20_000)),
                        Map.of("category", "HOSPITAL", "amount", BigDecimal.valueOf(10_000))),
                BigDecimal.valueOf(100_000));
        when(groupPurchaseService.list(any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(GroupPurchaseListResponse.builder().items(List.of()).hasNext(false).build());

        InsightCard card = collector.collect("m1", "p1");

        assertNotNull(card);
        List<com.aewol.domain.insight.dto.CategoryShare> breakdown = card.getCategoryBreakdown();
        assertEquals(3, breakdown.size());
        assertEquals("사료", breakdown.get(0).getLabel());
        assertEquals(70, breakdown.get(0).getPercentage());
        assertEquals("간식", breakdown.get(1).getLabel());
        assertEquals(20, breakdown.get(1).getPercentage());
        assertEquals("의료", breakdown.get(2).getLabel());
        assertEquals(10, breakdown.get(2).getPercentage());
    }

    @Test
    @DisplayName("카테고리가 4개를 넘으면 상위 4개만 남기고 나머지는 '기타'로 합친다")
    void should_collapseTailCategories_intoOther_whenMoreThanFourCategories() {
        givenBreakdown(
                null,
                List.of(
                        Map.of("category", "FOOD", "amount", BigDecimal.valueOf(40_000)),
                        Map.of("category", "SNACK", "amount", BigDecimal.valueOf(30_000)),
                        Map.of("category", "HOSPITAL", "amount", BigDecimal.valueOf(15_000)),
                        Map.of("category", "GROOMING", "amount", BigDecimal.valueOf(10_000)),
                        Map.of("category", "INSURANCE", "amount", BigDecimal.valueOf(3_000)),
                        Map.of("category", "DONATION", "amount", BigDecimal.valueOf(2_000))),
                BigDecimal.valueOf(100_000));
        when(groupPurchaseService.list(any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(GroupPurchaseListResponse.builder().items(List.of()).hasNext(false).build());

        InsightCard card = collector.collect("m1", "p1");

        assertNotNull(card);
        List<com.aewol.domain.insight.dto.CategoryShare> breakdown = card.getCategoryBreakdown();
        // 상위 4개(사료/간식/의료/미용) + 기타(보험 3,000 + 기부 2,000 = 5,000, 5%)
        assertEquals(5, breakdown.size());
        assertEquals("기타", breakdown.get(4).getLabel());
        assertEquals(5, breakdown.get(4).getPercentage());
    }

    @Test
    @DisplayName("보험이 1위가 아니면 CTA는 기존처럼 내역 보기로 유지된다")
    void should_keepWalletCta_when_topCategoryIsNotInsurance() {
        givenBreakdown(
                null,
                List.of(Map.of("category", "FOOD", "amount", BigDecimal.valueOf(10_000))),
                BigDecimal.valueOf(10_000));
        when(groupPurchaseService.list(any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(GroupPurchaseListResponse.builder().items(List.of()).hasNext(false).build());

        InsightCard card = collector.collect("m1", "p1");

        assertNotNull(card);
        assertEquals("/wallet", card.getCtaPath());
        assertEquals("내역 보기", card.getCtaLabel());
    }
}
