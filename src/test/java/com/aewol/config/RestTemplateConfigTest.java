package com.aewol.config;

import com.aewol.external.gemini.GeminiVisionClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * geminiRestTemplate 빈 주입(@Qualifier)과 공유 restTemplate 빈의 @Primary 지정이
 * 실제로 적용됐는지 확인한다. Lombok @RequiredArgsConstructor가 @Qualifier를 복사하지 않는
 * 함정 때문에, 컨텍스트가 정상 로드되는 것만으로는 이 배선이 올바른지 알 수 없다.
 */
@ActiveProfiles("test")
@SpringJUnitWebConfig(classes = AppConfig.class)
class RestTemplateConfigTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private GeminiVisionClient geminiVisionClient;

    @Test
    @DisplayName("GeminiVisionClient는 공유 restTemplate이 아닌 전용 geminiRestTemplate 빈을 주입받는다")
    void should_injectDedicatedRestTemplate_intoGeminiVisionClient() {
        RestTemplate sharedRestTemplate = (RestTemplate) context.getBean("restTemplate");
        RestTemplate injected = (RestTemplate) ReflectionTestUtils.getField(geminiVisionClient, "restTemplate");

        assertNotSame(sharedRestTemplate, injected);
    }

    @Test
    @DisplayName("GeminiVisionClient에 주입된 RestTemplate은 20초 read timeout이 설정되어 있다")
    void should_haveConfiguredReadTimeout_onInjectedRestTemplate() {
        RestTemplate injected = (RestTemplate) ReflectionTestUtils.getField(geminiVisionClient, "restTemplate");
        ClientHttpRequestFactory requestFactory = injected.getRequestFactory();

        Object readTimeout = ReflectionTestUtils.getField(requestFactory, "readTimeout");

        assertEquals(20000, readTimeout);
    }

    @Test
    @DisplayName("@Primary 덕분에 타입 기반 주입은 항상 공유 restTemplate 빈으로 고정된다")
    void should_resolveSharedBean_whenInjectingByType() {
        RestTemplate byType = context.getBean(RestTemplate.class);
        RestTemplate byName = (RestTemplate) context.getBean("restTemplate");

        assertSame(byName, byType);
    }
}
