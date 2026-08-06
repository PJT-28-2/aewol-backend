package com.aewol.config;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    // CODEF 전용 RestTemplate — 연결 5초 / 응답 대기 10초로 제한한다. 기본 restTemplate()은
    // 타임아웃이 전혀 없어서, CODEF가 응답을 지연하면 요청이 무기한 블로킹되고 그 호출이
    // @Transactional 메서드 안에 있으면 DB 커넥션 풀까지 고갈될 수 있다(CodeRabbit 지적,
    // 2026-08-06). 다른 외부 연동(Toss/Naver/Kakao 등)은 기존 restTemplate()을 그대로 쓰고
    // 있어서 그쪽 타임아웃 정책은 건드리지 않는다.
    @Bean
    public RestTemplate codefRestTemplate() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(5_000)
                .setSocketTimeout(10_000)
                .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(
                HttpClientBuilder.create().setDefaultRequestConfig(requestConfig).build());
        return new RestTemplate(factory);
    }
}
