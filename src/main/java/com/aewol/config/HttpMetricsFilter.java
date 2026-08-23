package com.aewol.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * HTTP 요청 소요 시간을 잰다.
 *
 * <p>어느 API가 느린지를 사후에 재현하지 않고도 알 수 있어야 한다. 지금까지는 문제를
 * 의심한 뒤에야 측정 도구를 따로 만들었다.
 *
 * <p><b>URI를 그대로 태그로 쓰지 않는다.</b> {@code /api/pets/123}처럼 id가 박힌 경로를
 * 태그로 쓰면 시계열이 무한히 늘어나 Prometheus가 죽는다(카디널리티 폭발). Spring이 매칭한
 * 패턴({@code /api/pets/{petId}})을 쓰고, 매칭되지 않은 요청은 한 덩어리로 묶는다.
 */
public class HttpMetricsFilter extends OncePerRequestFilter {

    private static final String UNMATCHED = "UNKNOWN";

    private final MeterRegistry registry;

    public HttpMetricsFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Timer.Sample sample = Timer.start(registry);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 패턴은 핸들러 매핑이 끝난 뒤에야 정해지므로 여기서 읽는다.
            sample.stop(Timer.builder("http.server.requests")
                    .tag("method", request.getMethod())
                    .tag("uri", uriTag(request))
                    .tag("status", String.valueOf(response.getStatus()))
                    .publishPercentileHistogram()
                    .register(registry));
        }
    }

    private String uriTag(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern != null) {
            return String.valueOf(pattern);
        }
        // 매칭 실패(404 등)는 경로를 그대로 남기면 공격자가 시계열을 무한히 만들 수 있다.
        return UNMATCHED;
    }
}
