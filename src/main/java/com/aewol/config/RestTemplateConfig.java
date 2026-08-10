package com.aewol.config;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Primary
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(10_000);
        return new RestTemplate(requestFactory);
    }

    // CODEF 전용 RestTemplate — 연결 5초 / 응답 대기 10초로 제한한다. 기본 restTemplate()은
    // 타임아웃이 전혀 없어서, CODEF가 응답을 지연하면 요청이 무기한 블로킹되고 그 호출이
    // @Transactional 메서드 안에 있으면 DB 커넥션 풀까지 고갈될 수 있다(CodeRabbit 지적,
    // 2026-08-06). 다른 외부 연동(Toss/Naver/Kakao 등)은 기존 restTemplate()을 그대로 쓰고
    // 있어서 그쪽 타임아웃 정책은 건드리지 않는다.
    //
    // connectionRequestTimeout(풀에서 연결을 빌려오는 대기 시간)도 같이 설정한다 — 이걸
    // 안 정하면 무제한 대기가 기본값이라 위 두 타임아웃을 걸어도 소용없다. 또한
    // HttpClients 기본 커넥션 풀은 라우트당 2개로 제한돼 있어서 동시 CODEF 호출이
    // 3건만 넘어가도 커넥션 확보 단계에서부터 막힌다(CodeRabbit 지적, 2026-08-06).
    // CODEF 호출은 oauth.codef.io / api.codef.io 두 라우트뿐이라 라우트당 20개면
    // 충분히 여유롭다.
    @Bean
    public RestTemplate codefRestTemplate() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(20);
        connectionManager.setDefaultMaxPerRoute(20);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(5_000)
                .setSocketTimeout(10_000)
                .setConnectionRequestTimeout(5_000)
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(
                HttpClientBuilder.create()
                        .setConnectionManager(connectionManager)
                        .setDefaultRequestConfig(requestConfig)
                        .build());
        return new RestTemplate(factory);
    }

    @Bean(name = "geminiRestTemplate")
    public RestTemplate geminiRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(20000);
        return new RestTemplate(factory);
    }

    // 이미지 생성 전용 — OCR과 달리 응답까지 10~15초가 걸린다(실측 전신 10.3초 / 프로필 12.2초).
    // geminiRestTemplate의 20초로는 여유가 없어 정상 응답도 타임아웃으로 끊길 수 있다.
    // 이 호출은 반드시 @Transactional 밖에서 이뤄져야 한다. 느린 외부 호출을 트랜잭션 안에
    // 두면 DB 커넥션이 그동안 점유돼 풀이 고갈될 수 있다(CODEF 사례, 2026-08-06).
    @Bean(name = "geminiImageRestTemplate")
    public RestTemplate geminiImageRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(60_000);
        return new RestTemplate(factory);
    }
}
