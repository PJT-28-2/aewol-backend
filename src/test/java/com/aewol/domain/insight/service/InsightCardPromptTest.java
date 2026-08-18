package com.aewol.domain.insight.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 예측 값이 프롬프트에 어떻게 실리는지 고정한다.
 *
 * <p>예측이 없을 때 프롬프트에 예측 자리만 남으면, 모델이 그 빈칸을 채우려고
 * 없는 숫자를 지어낼 여지가 생긴다. 없을 때는 흔적조차 없어야 한다.
 */
class InsightCardPromptTest {

    private InsightCard.InsightCardBuilder base() {
        return InsightCard.builder()
                .type(InsightCardType.SPENDING)
                .headline("이번 달 지출 100,000원")
                .facts("총 지출: 100,000원")
                .fallbackBody("대체 문구")
                .digest("d1");
    }

    @Test
    @DisplayName("예측이 있으면 사실과 구분해 덧붙인다")
    void should_appendProjection_when_present() {
        String prompt = base().projection("이 속도면 이달 말 약 310,000원 (남은 21일)").build().promptFacts();

        assertTrue(prompt.startsWith("총 지출: 100,000원"));
        assertTrue(prompt.contains("예측"));
        assertTrue(prompt.contains("이 속도면 이달 말 약 310,000원 (남은 21일)"));
    }

    @Test
    @DisplayName("예측이 없으면 사실만 넘긴다")
    void should_passFactsOnly_when_projectionMissing() {
        assertEquals("총 지출: 100,000원", base().build().promptFacts());
        assertEquals("총 지출: 100,000원", base().projection("   ").build().promptFacts());
    }
}
