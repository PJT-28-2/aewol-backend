package com.aewol.external.gemini;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class GeminiVisionClient {

    private final RestTemplate restTemplate;

    public GeminiVisionClient(@Qualifier("geminiRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
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
