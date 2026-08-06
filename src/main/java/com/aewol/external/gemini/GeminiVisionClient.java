package com.aewol.external.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GeminiVisionClient {

    private static final String GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=%s";

    private static final String RECEIPT_PROMPT =
            "당신은 반려동물 병원 영수증에서 정보를 추출하는 OCR 어시스턴트입니다. "
                    + "이미지에서 다음 스키마와 정확히 동일한 JSON 객체 하나만 출력하세요: "
                    + "{\"hospital_name\": string|null, \"treatment_date\": string|null, "
                    + "\"items\": [{\"name\": string, \"quantity\": number, \"amount\": number}], "
                    + "\"total_amount\": number|null, \"vet_name\": string|null}. "
                    + "규칙: "
                    + "1) JSON 객체 외에 어떠한 설명, 인사말, 마크다운 코드블록도 출력하지 마세요. "
                    + "2) 이미지가 영수증이 아니거나 특정 항목을 읽을 수 없으면 해당 필드를 null로 채운 동일한 스키마의 JSON을 출력하세요. "
                    + "3) items를 알 수 없으면 빈 배열 []로 출력하세요.";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${external.gemini.api-key:}")
    private String apiKey;

    public GeminiVisionClient(@Qualifier("geminiRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 영수증 이미지를 Gemini Vision API에 전송하여 OCR 결과를 받아옴.
     * 호출 실패/파싱 실패 시에도 예외를 전파하지 않고 "{}"를 반환한다 (human-in-the-loop 수동 입력으로 유도).
     */
    public String extractReceiptData(byte[] imageBytes, String mimeType) {
        log.info("Gemini Vision OCR 호출 - imageSize: {}, mimeType: {}", imageBytes.length, mimeType);
        try {
            String responseBody = callGemini(imageBytes, mimeType);
            return extractJsonFromResponse(responseBody);
        } catch (RestClientException e) {
            log.error("[GEMINI_OCR_FAILED] Gemini Vision API 호출 실패 - imageSize: {}, mimeType: {}",
                    imageBytes.length, mimeType, e);
            return "{}";
        } catch (Exception e) {
            log.error("[GEMINI_OCR_FAILED] Gemini Vision 응답 파싱 실패 - imageSize: {}, mimeType: {}",
                    imageBytes.length, mimeType, e);
            return "{}";
        }
    }

    private String callGemini(byte[] imageBytes, String mimeType) {
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", RECEIPT_PROMPT),
                                Map.of("inline_data", Map.of(
                                        "mime_type", mimeType,
                                        "data", Base64.getEncoder().encodeToString(imageBytes)
                                ))
                        ))
                )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        String url = String.format(GEMINI_ENDPOINT, apiKey);
        return restTemplate.postForObject(url, entity, String.class);
    }

    private String extractJsonFromResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode textNode = root.path("candidates").path(0)
                .path("content").path("parts").path(0)
                .path("text");

        if (textNode.isMissingNode() || textNode.isNull()) {
            throw new IllegalStateException("Gemini 응답에서 text를 찾을 수 없습니다.");
        }

        String extracted = stripCodeFence(textNode.asText());
        objectMapper.readTree(extracted); // Gemini가 JSON이 아닌 텍스트로 응답한 경우 검증에서 걸러내 "{}" 처리되도록 함
        return extracted;
    }

    private String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(json)?", "").trim();
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }

    /**
     * 가맹점명 기반 카테고리 분류 (자동 태깅 2차)
     */
    public String classifyMerchant(String merchantName) {
        // TODO: Gemini API를 이용한 가맹점 카테고리 분류
        log.info("Gemini 가맹점 분류 요청 - merchantName: {}", merchantName);
        return "ETC";
    }
}
