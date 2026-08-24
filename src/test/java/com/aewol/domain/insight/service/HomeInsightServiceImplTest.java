package com.aewol.domain.insight.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.domain.insight.dto.HomeInsightResponse;
import com.aewol.domain.insight.mapper.HomeInsightMapper;
import com.aewol.external.openai.OpenAiChatClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HomeInsightServiceImplTest {

    @Mock OpenAiChatClient openAiChatClient;
    @Mock HomeInsightMapper homeInsightMapper;

    private HomeInsightServiceImpl service(List<InsightCardCollector> collectors, boolean enabled) {
        return new HomeInsightServiceImpl(collectors, openAiChatClient, homeInsightMapper, 24, enabled);
    }

    /** 재료를 항상 같은 값으로 내놓는 수집기. */
    private InsightCardCollector collector(InsightCardType type, InsightCard card) {
        return new InsightCardCollector() {
            @Override public InsightCardType type() {
                return type;
            }
            @Override public InsightCard collect(String memberId, String petId) {
                return card;
            }
        };
    }

    private InsightCard card(InsightCardType type, String digest) {
        return InsightCard.builder()
                .type(type)
                .headline("지원 7건")
                .facts("사실 목록")
                .fallbackBody("데이터로 만든 대체 문구입니다.")
                .ctaLabel("전체 보기")
                .ctaPath("/support-programs")
                .digest(digest)
                .build();
    }

    @Test
    @DisplayName("캐시가 없으면 요청 경로에서 LLM을 부르지 않고 대체 문구를 바로 돌려준다")
    void should_returnFallbackWithoutLlm_when_cacheMisses() {
        when(homeInsightMapper.findFreshByMemberId("m1")).thenReturn(List.of());

        List<HomeInsightResponse> cards =
                service(List.of(collector(InsightCardType.SUPPORT, card(InsightCardType.SUPPORT, "d1"))), true)
                        .getCards("m1", null);

        assertEquals(1, cards.size());
        assertEquals("데이터로 만든 대체 문구입니다.", cards.get(0).getBody());
        assertTrue(cards.get(0).isFallback());
        verify(openAiChatClient, never()).complete(anyString(), anyString());
        verify(homeInsightMapper, never()).upsert(any());
    }

    // 도넛 데이터는 캐시가 아니라 매 요청 계산한 값이다. 캐시 미스라고 빼면
    // 홈 화면에서 도넛 차트만 사라진 카드가 내려간다.
    @Test
    @DisplayName("캐시 미스 응답에도 도넛 차트 데이터와 추천 상품을 담는다")
    void should_includeCategoryBreakdown_when_cacheMisses() {
        when(homeInsightMapper.findFreshByMemberId("m1")).thenReturn(List.of());
        InsightCard card = InsightCard.builder()
                .type(InsightCardType.SPENDING)
                .headline("이번 달 지출 100,000원")
                .facts("총 지출: 100,000원")
                .fallbackBody("대체 문구")
                .ctaLabel("내역 보기")
                .ctaPath("/wallet/history")
                .digest("d1")
                .recommendedProducts(List.of())
                .categoryBreakdown(List.of(com.aewol.domain.insight.dto.CategoryShare.builder()
                        .label("사료")
                        .amount(java.math.BigDecimal.valueOf(41000))
                        .percentage(41)
                        .build()))
                .build();

        List<HomeInsightResponse> cards =
                service(List.of(collector(InsightCardType.SPENDING, card)), true).getCards("m1", null);

        assertEquals(1, cards.get(0).getCategoryBreakdown().size());
        assertEquals("사료", cards.get(0).getCategoryBreakdown().get(0).getLabel());
        assertEquals(List.of(), cards.get(0).getRecommendedProducts());
    }

    @Test
    @DisplayName("생성된 문구를 캐시에 저장한다")
    void should_storeGeneratedBody() {
        when(homeInsightMapper.findFreshByMemberId("m1")).thenReturn(List.of());
        when(openAiChatClient.complete(anyString(), anyString())).thenReturn("모델이 쓴 문장입니다.");

        int generated = service(List.of(collector(InsightCardType.SUPPORT, card(InsightCardType.SUPPORT, "d1"))), true)
                .warmUp("m1", null);

        assertEquals(1, generated);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(homeInsightMapper).upsert(captor.capture());
        assertEquals("SUPPORT", captor.getValue().get("cardType"));
        assertEquals("N", captor.getValue().get("fallback"));
    }

    @Test
    @DisplayName("예측은 요청 경로에서도 카드 재료에서 별도 필드로만 전달한다")
    void should_exposeProjectionSeparately_withoutSendingItToLlm() {
        when(homeInsightMapper.findFreshByMemberId("m1")).thenReturn(List.of());
        InsightCard card = InsightCard.builder()
                .type(InsightCardType.SPENDING)
                .headline("이번 달 지출 100,000원")
                .facts("총 지출: 100,000원")
                .fallbackBody("대체 문구")
                .projection("이 속도면 이달 말 약 310,000원")
                .ctaLabel("내역 보기")
                .ctaPath("/wallet")
                .digest("d1")
                .build();

        List<HomeInsightResponse> cards =
                service(List.of(collector(InsightCardType.SPENDING, card)), true).getCards("m1", null);

        verify(openAiChatClient, never()).complete(anyString(), anyString());
        assertEquals("이 속도면 이달 말 약 310,000원", cards.get(0).getProjection());
        assertEquals("대체 문구", cards.get(0).getBody());
    }

    // 외부 호출이 실패해도 홈 화면은 떠야 한다. 카드가 사라지거나 빈 문구가 되면
    // 사용자에게는 앱이 고장 난 것으로 보인다.
    @Test
    @DisplayName("LLM 호출이 실패하면 데이터로 만든 대체 문구를 쓴다")
    void should_useFallbackBody_when_llmFails() {
        when(homeInsightMapper.findFreshByMemberId("m1")).thenReturn(List.of());
        when(openAiChatClient.complete(anyString(), anyString())).thenReturn(null);

        List<HomeInsightResponse> cards =
                service(List.of(collector(InsightCardType.SUPPORT, card(InsightCardType.SUPPORT, "d1"))), true)
                        .getCards("m1", null);

        assertEquals("데이터로 만든 대체 문구입니다.", cards.get(0).getBody());
        assertTrue(cards.get(0).isFallback());
    }

    // 같은 입력에 두 번 돈을 쓸 이유가 없다.
    @Test
    @DisplayName("재료가 그대로면 캐시를 쓰고 LLM을 부르지 않는다")
    void should_reuseCache_when_sourceUnchanged() {
        Map<String, Object> cached = new HashMap<>();
        cached.put("card_type", "SUPPORT");
        cached.put("body", "어제 만든 문장입니다.");
        cached.put("source_digest", "d1");
        cached.put("fallback", "N");
        cached.put("generated_at", "2026-08-12T04:30:00");
        when(homeInsightMapper.findFreshByMemberId("m1")).thenReturn(List.of(cached));

        List<HomeInsightResponse> cards =
                service(List.of(collector(InsightCardType.SUPPORT, card(InsightCardType.SUPPORT, "d1"))), true)
                        .getCards("m1", null);

        assertEquals("어제 만든 문장입니다.", cards.get(0).getBody());
        verify(openAiChatClient, never()).complete(anyString(), anyString());
        verify(homeInsightMapper, never()).upsert(any());
    }

    @Test
    @DisplayName("재료가 바뀌면 홈 요청은 대체 문구를 주고, 배치는 LLM으로 다시 만든다")
    void should_regenerate_when_sourceChanged() {
        Map<String, Object> cached = new HashMap<>();
        cached.put("card_type", "SUPPORT");
        cached.put("body", "예전 문장");
        cached.put("source_digest", "d0");
        cached.put("fallback", "N");
        when(homeInsightMapper.findFreshByMemberId("m1")).thenReturn(List.of(cached));
        when(openAiChatClient.complete(anyString(), anyString())).thenReturn("새 문장");
        HomeInsightServiceImpl service =
                service(List.of(collector(InsightCardType.SUPPORT, card(InsightCardType.SUPPORT, "d1"))), true);

        List<HomeInsightResponse> cards = service.getCards("m1", null);
        assertEquals("데이터로 만든 대체 문구입니다.", cards.get(0).getBody());
        verify(openAiChatClient, never()).complete(anyString(), anyString());

        service.warmUp("m1", null);
        verify(homeInsightMapper).upsert(any());
    }

    // 근거가 사라졌는데 그 근거로 쓴 문장을 계속 띄우면 카드가 거짓말을 한다.
    @Test
    @DisplayName("보여줄 데이터가 없으면 캐시가 있어도 카드를 내보내지 않는다")
    void should_dropCard_when_dataDisappeared() {
        Map<String, Object> cached = new HashMap<>();
        cached.put("card_type", "SUPPORT");
        cached.put("body", "지원 7건이 있어요");
        cached.put("source_digest", "d1");
        when(homeInsightMapper.findFreshByMemberId("m1")).thenReturn(List.of(cached));

        List<HomeInsightResponse> cards =
                service(List.of(collector(InsightCardType.SUPPORT, null)), true).getCards("m1", null);

        assertTrue(cards.isEmpty());
    }

    @Test
    @DisplayName("수집 중 예외가 나도 나머지 카드는 살아남는다")
    void should_keepOtherCards_when_oneCollectorThrows() {
        when(homeInsightMapper.findFreshByMemberId("m1")).thenReturn(List.of());
        when(openAiChatClient.complete(anyString(), anyString())).thenReturn("정상 문장");

        InsightCardCollector broken = new InsightCardCollector() {
            @Override public InsightCardType type() {
                return InsightCardType.SPENDING;
            }
            @Override public InsightCard collect(String memberId, String petId) {
                throw new IllegalStateException("집계 실패");
            }
        };

        List<HomeInsightResponse> cards = service(
                List.of(broken, collector(InsightCardType.SUPPORT, card(InsightCardType.SUPPORT, "d1"))), true)
                .getCards("m1", null);

        assertEquals(1, cards.size());
        assertEquals("SUPPORT", cards.get(0).getType());
    }

    @Test
    @DisplayName("기능을 끄면 외부 호출 없이 빈 목록을 돌려준다")
    void should_returnEmpty_when_disabled() {
        List<HomeInsightResponse> cards =
                service(List.of(collector(InsightCardType.SUPPORT, card(InsightCardType.SUPPORT, "d1"))), false)
                        .getCards("m1", null);

        assertTrue(cards.isEmpty());
        verify(openAiChatClient, never()).complete(anyString(), anyString());
    }

    // body 컬럼은 300자다. 모델이 규칙을 어겨도 저장이 깨지면 안 된다.
    @Test
    @DisplayName("모델 응답이 컬럼 길이를 넘으면 잘라서 저장한다")
    void should_trimBody_when_tooLong() {
        when(homeInsightMapper.findFreshByMemberId("m1")).thenReturn(List.of());
        when(openAiChatClient.complete(anyString(), anyString())).thenReturn("가".repeat(500));

        service(List.of(collector(InsightCardType.SUPPORT, card(InsightCardType.SUPPORT, "d1"))), true)
                .warmUp("m1", null);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(homeInsightMapper).upsert(captor.capture());
        assertEquals(300, String.valueOf(captor.getValue().get("body")).length());
    }
}
