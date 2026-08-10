package com.aewol.external.gemini;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeminiVisionClientTest {

    @Test
    @DisplayName("classifyMerchant는 아직 미구현 상태라 항상 ETC를 반환한다")
    void should_returnEtc_whenClassifyingMerchant() {
        GeminiVisionClient client = new GeminiVisionClient(new RestTemplate());

        String result = client.classifyMerchant("애월동물병원");

        assertEquals("ETC", result);
    }
}
