package com.aewol.external.openai;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * OpenAI Chat Completions 호출.
 *
 * <p>홈 인사이트 카드 문구를 만드는 데만 쓴다. 이미지 생성은 이 키의 권한 밖이라
 * (접근 가능한 이미지 모델이 없다) 캐릭터 생성은 계속 Gemini 를 쓴다.
 */
@Slf4j
@Component
public class OpenAiChatClient {

    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    public OpenAiChatClient(@Qualifier("openAiRestTemplate") RestTemplate restTemplate,
                            @Value("${external.openai.api-key:}") String apiKey,
                            @Value("${external.openai.chat-model:gpt-4o-mini}") String model,
                            @Value("${external.openai.max-tokens:200}") int maxTokens) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 실패하면 예외를 던지지 않고 null 을 돌려준다. 홈 카드는 문구가 없으면 데이터로
     * 만든 대체 문구를 쓰면 되고, 외부 장애가 홈 화면을 막아서는 안 된다.
     */
    public String complete(String systemPrompt, String userPrompt) {
        if (!isConfigured()) {
            log.warn("[OpenAI] api-key 미설정 — 호출을 건너뜁니다.");
            return null;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                // gpt-4o-mini 계열은 max_tokens 를 쓴다. 추론 모델(gpt-5-*)로 바꾸려면
                // max_completion_tokens 로 이름이 달라지고, 짧은 요약에도 추론 토큰을
                // 1,000개 넘게 먹어 이 용도에는 맞지 않는다.
                "max_tokens", maxTokens,
                "temperature", 0.5);

        try {
            Map<?, ?> response = restTemplate.postForObject(ENDPOINT, new HttpEntity<>(body, headers), Map.class);
            return firstMessageContent(response);
        } catch (Exception e) {
            log.warn("[OpenAI] 호출 실패 - model: {}, reason: {}", model, e.getMessage());
            return null;
        }
    }

    private String firstMessageContent(Map<?, ?> response) {
        if (response == null) {
            return null;
        }
        Object choices = response.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            log.warn("[OpenAI] 응답에 choices 가 없습니다.");
            return null;
        }
        if (!(list.get(0) instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)) {
            return null;
        }
        Object content = message.get("content");
        if (content == null) {
            return null;
        }
        String text = String.valueOf(content).trim();
        return text.isEmpty() ? null : text;
    }
}
