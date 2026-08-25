package com.aewol.common.health;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 메트릭 엔드포인트의 접근 통제.
 *
 * <p>이 응답에는 내부 API 경로·커넥션 풀 상태·JVM 상황이 그대로 담긴다. 공개되면 서비스
 * 구조가 그대로 읽힌다.
 */
class MetricsControllerTest {

    private PrometheusMeterRegistry registry() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.counter("test.counter").increment();
        return registry;
    }

    /*
     * 토큰을 설정하지 않은 환경에서 메트릭이 무방비로 열리면 안 된다. 설정을 깜빡하는 쪽이
     * 훨씬 흔하므로 기본값을 꺼짐으로 둔다.
     */
    @Test
    @DisplayName("토큰이 설정되지 않으면 엔드포인트가 없는 것처럼 동작한다")
    void should_disableEndpoint_when_tokenNotConfigured() {
        MetricsController controller = new MetricsController(registry(), "");

        assertEquals(HttpStatus.NOT_FOUND, controller.scrape(null, null).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.scrape("아무값", null).getStatusCode());
    }

    @Test
    @DisplayName("토큰이 맞아야 메트릭을 준다")
    void should_requireMatchingToken() {
        MetricsController controller = new MetricsController(registry(), "secret");

        assertEquals(HttpStatus.NOT_FOUND, controller.scrape(null, null).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.scrape("wrong", null).getStatusCode());
        // 길이가 다른 값, 접두사가 같은 값 모두 막힌다.
        assertEquals(HttpStatus.NOT_FOUND, controller.scrape("secre", null).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.scrape("secret2", null).getStatusCode());

        ResponseEntity<String> ok = controller.scrape("secret", null);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertTrue(ok.getBody().contains("test_counter"));
    }

    @Test
    @DisplayName("운영 Prometheus의 Bearer 토큰도 받는다")
    void should_acceptBearerToken() {
        MetricsController controller = new MetricsController(registry(), "secret");

        assertEquals(HttpStatus.NOT_FOUND, controller.scrape(null, "Bearer wrong").getStatusCode());
        ResponseEntity<String> ok = controller.scrape(null, "Bearer secret");
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertTrue(ok.getBody().contains("test_counter"));
    }

    // 401이 아니라 404를 준다. 틀렸다는 사실조차 알려주지 않으면 존재 여부부터 숨겨진다.
    @Test
    @DisplayName("틀린 토큰에도 존재 여부를 알려주지 않는다")
    void should_hideExistence_when_tokenWrong() {
        MetricsController controller = new MetricsController(registry(), "secret");

        assertEquals(controller.scrape(null, null).getStatusCode(),
                controller.scrape("wrong", null).getStatusCode());
    }
}
