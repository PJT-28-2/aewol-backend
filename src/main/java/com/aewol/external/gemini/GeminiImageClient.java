package com.aewol.external.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
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
 * 이미지를 입력받아 스타일이 바뀐 이미지를 돌려주는 Gemini 호출 담당.
 *
 * <p>영수증 OCR을 담당하는 {@link GeminiVisionClient}와 엔드포인트 형태는 같지만
 * 모델과 응답 처리가 다르다. OCR은 텍스트를, 이쪽은 base64 이미지를 꺼낸다.
 *
 * <p>주의: 이 모델은 알파 채널을 만들지 못한다. 프롬프트로 투명 배경을 요구하면
 * 투명을 나타내는 격자무늬를 그림으로 그려버린다. 그래서 프롬프트에서 단색 배경을
 * 받아 낸 뒤 {@code ChromaKeyRemover}로 배경을 빼내는 방식을 쓴다.
 */
@Slf4j
@Component
public class GeminiImageClient {

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${external.gemini.api-key:}")
    private String apiKey;

    @Value("${external.gemini.image-model:gemini-2.5-flash-image}")
    private String imageModel;

    public GeminiImageClient(@Qualifier("geminiImageRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 입력 이미지와 프롬프트로 새 이미지를 생성한다.
     *
     * @return 생성된 이미지 바이트. 실패하면 null을 돌려주고 호출자가 원본을 유지하도록 한다.
     */
    public byte[] generate(byte[] sourceImage, String mimeType, String prompt) {
        if (!isConfigured()) {
            log.warn("[GEMINI_IMAGE_SKIPPED] API 키가 설정되지 않아 이미지 생성을 건너뜁니다.");
            return null;
        }

        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> body = Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(
                                    Map.of("text", prompt),
                                    Map.of("inline_data", Map.of(
                                            "mime_type", mimeType,
                                            "data", Base64.getEncoder().encodeToString(sourceImage)
                                    ))
                            ))
                    )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String response = restTemplate.postForObject(
                    String.format(ENDPOINT, imageModel, apiKey),
                    new HttpEntity<>(body, headers),
                    String.class);

            byte[] generated = extractImage(response);
            log.info("Gemini 이미지 생성 완료 - model: {}, 입력 {}바이트 → 출력 {}바이트, {}ms",
                    imageModel, sourceImage.length,
                    generated == null ? 0 : generated.length,
                    System.currentTimeMillis() - startedAt);
            return generated;
        } catch (Exception e) {
            log.error("[GEMINI_IMAGE_FAILED] 이미지 생성 실패 - model: {}, 입력 {}바이트, {}ms",
                    imageModel, sourceImage.length, System.currentTimeMillis() - startedAt, e);
            return null;
        }
    }

    /** 응답의 parts에서 inlineData(base64 이미지)를 찾아 디코딩한다. */
    private byte[] extractImage(String responseBody) throws Exception {
        if (responseBody == null) {
            return null;
        }
        JsonNode parts = objectMapper.readTree(responseBody)
                .path("candidates").path(0).path("content").path("parts");
        for (JsonNode part : parts) {
            JsonNode data = part.path("inlineData").path("data");
            if (data.isMissingNode()) {
                data = part.path("inline_data").path("data");
            }
            if (!data.isMissingNode() && !data.isNull()) {
                return Base64.getDecoder().decode(data.asText());
            }
        }
        log.warn("[GEMINI_IMAGE_EMPTY] 응답에 이미지가 없습니다.");
        return null;
    }
}
