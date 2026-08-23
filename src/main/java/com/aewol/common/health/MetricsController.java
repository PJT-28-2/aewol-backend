package com.aewol.common.health;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prometheus 스크레이핑 엔드포인트.
 *
 * <p>이 응답에는 내부 API 경로·커넥션 풀 상태·JVM 상황이 그대로 담긴다. 공개하면 서비스
 * 구조를 그대로 읽히므로 전용 토큰을 요구한다.
 *
 * <p><b>JWT를 쓰지 않는 이유</b>: Prometheus는 토큰을 갱신할 수 없다. access token은 30분이면
 * 만료되므로 스크레이핑이 곧 끊긴다. 그래서 만료 없는 전용 토큰을 환경변수로 받는다.
 *
 * <p><b>기본값은 꺼짐</b>이다. {@code METRICS_TOKEN}이 없으면 엔드포인트가 아예 없는 것처럼
 * 404를 준다. 설정을 깜빡한 환경에서 메트릭이 무방비로 열리는 쪽보다, 안 열리는 쪽이 낫다.
 */
@Slf4j
@RestController
public class MetricsController {

    private final PrometheusMeterRegistry registry;
    private final String expectedToken;

    public MetricsController(PrometheusMeterRegistry registry,
                             @Value("${metrics.token:}") String expectedToken) {
        this.registry = registry;
        this.expectedToken = expectedToken;
        if (!StringUtils.hasText(expectedToken)) {
            log.info("[METRICS] metrics.token이 없어 /api/metrics를 비활성화합니다.");
        }
    }

    @GetMapping(value = "/api/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> scrape(
            @RequestHeader(value = "X-Metrics-Token", required = false) String token) {
        if (!StringUtils.hasText(expectedToken)) {
            return ResponseEntity.notFound().build();
        }
        if (!matches(token)) {
            // 토큰이 틀렸다는 사실도 알려주지 않는다. 존재 여부부터 숨긴다.
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(registry.scrape());
    }

    /**
     * 길이와 내용을 상수 시간으로 비교한다.
     *
     * <p>보통의 문자열 비교는 첫 글자가 다르면 곧바로 끝나므로, 응답 시간 차이로 토큰을 한
     * 글자씩 맞춰볼 수 있다.
     */
    private boolean matches(String token) {
        if (token == null) {
            return false;
        }
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                expectedToken.getBytes(StandardCharsets.UTF_8));
    }
}
