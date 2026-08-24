package com.aewol.external.paddleocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class PaddleOcrClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${external.ocr-service.url:http://localhost:8000}")
    private String ocrServiceUrl;

    public PaddleOcrClient(@Qualifier("ocrServiceRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 영수증 이미지를 자체 호스팅 PaddleOCR 서비스(ocr-service)로 전송해 구조화된 JSON을 받아옴.
     * 호출/파싱 실패 시에도 예외를 전파하지 않고 "{}"를 반환한다 (human-in-the-loop 수동 입력으로 유도).
     */
    public String extractReceiptData(byte[] imageBytes, String mimeType) {
        log.info("PaddleOCR 영수증 추출 호출 - imageSize: {}, mimeType: {}", imageBytes.length, mimeType);
        try {
            String responseBody = callOcrService(imageBytes, mimeType);
            return validateJsonObject(responseBody);
        } catch (RestClientException e) {
            log.error("[PADDLEOCR_FAILED] PaddleOCR 서비스 호출 실패 - imageSize: {}, mimeType: {}, cause: {}",
                    imageBytes.length, mimeType, e.getClass().getSimpleName());
            return "{}";
        } catch (Exception e) {
            log.error("[PADDLEOCR_FAILED] PaddleOCR 응답 파싱 실패 - imageSize: {}, mimeType: {}, cause: {}",
                    imageBytes.length, mimeType, e.getClass().getSimpleName());
            return "{}";
        }
    }

    private String callOcrService(byte[] imageBytes, String mimeType) {
        ByteArrayResource imageResource = new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return "receipt";
            }
        };

        HttpHeaders imagePartHeaders = new HttpHeaders();
        imagePartHeaders.setContentType(MediaType.parseMediaType(mimeType));
        HttpEntity<ByteArrayResource> imagePart = new HttpEntity<>(imageResource, imagePartHeaders);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", imagePart);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

        String url = ocrServiceUrl + "/extract-receipt";
        return restTemplate.postForObject(url, entity, String.class);
    }

    private String validateJsonObject(String responseBody) throws Exception {
        JsonNode node = objectMapper.readTree(responseBody);
        if (!node.isObject()) {
            throw new IllegalStateException("PaddleOCR 응답이 JSON 객체가 아닙니다.");
        }
        return responseBody;
    }
}
