package com.aewol.external.gemini;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiVisionClient {

    private final RestTemplate restTemplate;

    @Value("${external.gemini.api-key:}")
    private String apiKey;

    /**
     * 영수증 이미지를 Gemini Vision API에 전송하여 OCR 결과를 받아옴
     * 구현 시 Gemini REST API 호출 로직 추가
     */
    public String extractReceiptData(byte[] imageBytes, String mimeType) {
        // TODO: Gemini Vision API 호출 구현
        log.info("Gemini Vision OCR 호출 - imageSize: {}, mimeType: {}", imageBytes.length, mimeType);
        return "{}";
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
