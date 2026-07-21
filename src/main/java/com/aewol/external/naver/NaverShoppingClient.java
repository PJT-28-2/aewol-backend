package com.aewol.external.naver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NaverShoppingClient {

    private final RestTemplate restTemplate;

    @Value("${external.naver.client-id:}")
    private String clientId;

    @Value("${external.naver.client-secret:}")
    private String clientSecret;

    private static final String SEARCH_URL = "https://openapi.naver.com/v1/search/shop.json";

    public Map<String, Object> searchProducts(String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Naver-Client-Id", clientId);
        headers.set("X-Naver-Client-Secret", clientSecret);

        String url = SEARCH_URL + "?query=" + query + "&display=10&sort=sim";
        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, request, Map.class);
        return response.getBody();
    }
}
