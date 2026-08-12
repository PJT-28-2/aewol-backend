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

    // TossPayments 전용 RestTemplate — codefRestTemplate()과 동일한 이유로 커넥션 풀링 +
    // connectionRequestTimeout을 명시한다. confirm/cancel은 실제 돈이 오가는 호출이라
    // 기본 restTemplate()(풀링 없는 SimpleClientHttpRequestFactory, connectionRequestTimeout
    // 미설정)로는 동시 요청 시 커넥션 확보 단계에서부터 막힐 수 있다. 소켓 타임아웃은
    // CODEF(10초)보다 여유를 둔다 — confirm 호출 안에서 카드사 승인까지 기다려야 하므로.
    @Bean
    public RestTemplate tossRestTemplate() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(20);
        connectionManager.setDefaultMaxPerRoute(20);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(5_000)
                .setSocketTimeout(20_000)
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

    // ocr-service 전용 RestTemplate — RapidOCR(CPU) 실측 응답 시간은 영수증당
    // 1.4~3.6초지만, 배포 환경 사양이 로컬보다 낮을 수 있어 여유를 크게 둔다.
    @Bean(name = "ocrServiceRestTemplate")
    public RestTemplate ocrServiceRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(120000);
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
