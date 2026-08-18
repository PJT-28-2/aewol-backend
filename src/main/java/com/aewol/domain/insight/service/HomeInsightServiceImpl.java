package com.aewol.domain.insight.service;

import com.aewol.domain.insight.dto.HomeInsightResponse;
import com.aewol.domain.insight.mapper.HomeInsightMapper;
import com.aewol.external.openai.OpenAiChatClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

/**
 * 홈 인사이트 카드를 만들고 캐시한다.
 *
 * <p>LLM 호출은 홈을 열 때마다 하면 비용과 지연이 그대로 붙는다. 그래서 회원·카드
 * 종류별로 결과를 저장하고, 만료되기 전까지는 저장된 것을 그대로 쓴다. 배치가 새벽에
 * 미리 채워두므로 실사용에서는 대부분 캐시 적중이다.
 *
 * <p>이 클래스에 트랜잭션을 걸지 않는 것은 의도된 것이다. 외부 호출이 트랜잭션 안에
 * 있으면 그동안 DB 커넥션이 점유돼 풀이 고갈될 수 있다. 저장은 카드별 upsert 한 문장씩이라
 * 그 자체로 원자적이다.
 */
@Slf4j
@Service
public class HomeInsightServiceImpl implements HomeInsightService {

    private static final String SYSTEM_PROMPT_PATH = "prompts/home-insight-system.txt";

    private final List<InsightCardCollector> collectors;
    private final OpenAiChatClient openAiChatClient;
    private final HomeInsightMapper homeInsightMapper;
    private final int ttlHours;
    private final boolean enabled;

    private String systemPrompt;

    public HomeInsightServiceImpl(List<InsightCardCollector> collectors,
                                  OpenAiChatClient openAiChatClient,
                                  HomeInsightMapper homeInsightMapper,
                                  @Value("${insight.ttl-hours:24}") int ttlHours,
                                  @Value("${insight.enabled:true}") boolean enabled) {
        this.collectors = collectors;
        this.openAiChatClient = openAiChatClient;
        this.homeInsightMapper = homeInsightMapper;
        this.ttlHours = ttlHours;
        this.enabled = enabled;
    }

    @Override
    public List<HomeInsightResponse> getCards(String memberId, String petId) {
        if (!enabled) {
            return List.of();
        }
        Map<String, Map<String, Object>> cached = cachedByType(memberId);

        List<HomeInsightResponse> cards = new ArrayList<>();
        for (InsightCardCollector collector : collectors) {
            String type = collector.type().name();
            InsightCard card = safeCollect(collector, memberId, petId);
            if (card == null) {
                // 재료가 사라졌으면 예전 캐시도 보여주지 않는다. 사라진 근거로 만든
                // 문장을 계속 띄우면 사실과 어긋난다.
                continue;
            }

            Map<String, Object> hit = cached.get(type);
            if (hit != null && sameSource(hit, card)) {
                cards.add(toResponse(card, hit));
                continue;
            }
            cards.add(generateAndStore(memberId, card));
        }
        return cards;
    }

    @Override
    public int warmUp(String memberId, String petId) {
        if (!enabled) {
            return 0;
        }
        int generated = 0;
        Map<String, Map<String, Object>> cached = cachedByType(memberId);
        for (InsightCardCollector collector : collectors) {
            InsightCard card = safeCollect(collector, memberId, petId);
            if (card == null) {
                continue;
            }
            Map<String, Object> hit = cached.get(collector.type().name());
            if (hit != null && sameSource(hit, card)) {
                continue;
            }
            generateAndStore(memberId, card);
            generated++;
        }
        return generated;
    }

    /** 수집 중 예외가 한 카드를 넘어 홈 전체를 막지 않게 한다. */
    private InsightCard safeCollect(InsightCardCollector collector, String memberId, String petId) {
        try {
            return collector.collect(memberId, petId);
        } catch (RuntimeException e) {
            log.warn("[Insight] 카드 재료 수집 실패 - type: {}, memberId: {}, reason: {}",
                    collector.type(), memberId, e.getMessage());
            return null;
        }
    }

    private HomeInsightResponse generateAndStore(String memberId, InsightCard card) {
        // 예측은 응답의 projection 필드에서만 보여준다. 프롬프트에 섞으면 모델 본문과
        // 별도 전망 영역에 같은 내용이 중복될 수 있다.
        String body = openAiChatClient.complete(systemPrompt(), card.getFacts());
        boolean fallback = body == null;
        if (fallback) {
            body = card.getFallbackBody();
        }
        body = trimToColumn(body);

        Map<String, Object> row = new HashMap<>();
        row.put("memberId", memberId);
        row.put("cardType", card.getType().name());
        row.put("headline", card.getHeadline());
        row.put("body", body);
        row.put("ctaLabel", card.getCtaLabel());
        row.put("ctaPath", card.getCtaPath());
        row.put("sourceDigest", card.getDigest());
        row.put("fallback", fallback ? "Y" : "N");
        row.put("ttlHours", ttlHours);
        try {
            homeInsightMapper.upsert(row);
        } catch (RuntimeException e) {
            // 캐시 저장 실패가 카드 노출을 막을 이유는 없다. 다음 호출에 다시 만든다.
            log.warn("[Insight] 카드 캐시 저장 실패 - type: {}, reason: {}", card.getType(), e.getMessage());
        }

        return HomeInsightResponse.builder()
                .type(card.getType().name())
                .headline(card.getHeadline())
                .body(body)
                .projection(card.getProjection())
                .ctaLabel(card.getCtaLabel())
                .ctaPath(card.getCtaPath())
                .fallback(fallback)
                .build();
    }

    private Map<String, Map<String, Object>> cachedByType(String memberId) {
        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
        try {
            for (Map<String, Object> row : homeInsightMapper.findFreshByMemberId(memberId)) {
                byType.put(String.valueOf(row.get("card_type")), row);
            }
        } catch (RuntimeException e) {
            log.warn("[Insight] 카드 캐시 조회 실패 - memberId: {}, reason: {}", memberId, e.getMessage());
        }
        return byType;
    }

    /** 재료가 그대로면 다시 만들지 않는다. 같은 입력에 돈을 두 번 쓸 이유가 없다. */
    private static boolean sameSource(Map<String, Object> cached, InsightCard card) {
        Object digest = cached.get("source_digest");
        return digest != null && String.valueOf(digest).equals(card.getDigest());
    }

    private static HomeInsightResponse toResponse(InsightCard card, Map<String, Object> cached) {
        Object generatedAt = cached.get("generated_at");
        return HomeInsightResponse.builder()
                .type(card.getType().name())
                // 제목은 캐시하지 않고 매번 데이터로 다시 만든다. 숫자가 바뀌었는데
                // 옛 제목이 남아 있으면 카드가 거짓말을 하게 된다.
                .headline(card.getHeadline())
                .body(String.valueOf(cached.get("body")))
                // 예측도 제목과 같은 이유로 캐시하지 않는다. 어제 계산한 '이달 말 예상'을
                // 오늘 그대로 보여주면 남은 일수가 어긋난다.
                .projection(card.getProjection())
                .ctaLabel(card.getCtaLabel())
                .ctaPath(card.getCtaPath())
                .fallback("Y".equals(String.valueOf(cached.get("fallback"))))
                .generatedAt(generatedAt == null ? null : String.valueOf(generatedAt))
                .build();
    }

    /** body 컬럼은 300자다. 모델이 규칙을 어겨 길게 써도 저장이 깨지지 않게 한다. */
    private static String trimToColumn(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.trim();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300);
    }

    private String systemPrompt() {
        if (systemPrompt == null) {
            try {
                byte[] bytes = FileCopyUtils.copyToByteArray(
                        new ClassPathResource(SYSTEM_PROMPT_PATH).getInputStream());
                systemPrompt = new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("프롬프트 파일을 읽지 못했습니다: " + SYSTEM_PROMPT_PATH, e);
            }
        }
        return systemPrompt;
    }
}
