package com.aewol.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 메트릭 수집.
 *
 * <p>Spring Boot가 아니라 actuator 자동 설정이 없다. 레지스트리와 바인딩을 직접 등록한다.
 *
 * <p>가장 중요한 것은 <b>커넥션 풀</b>이다. #338에서 결제가 트랜잭션 안에서 카카오 API를
 * 호출해 풀이 고갈될 수 있다는 걸 발견했는데, 그건 HikariCP가 이미 알고 있던 값이었다.
 * {@code hikaricp.connections.pending}이 0보다 커지는 것만 봤어도 훨씬 빨리 알았을 것이다.
 * 이제 그 값이 밖으로 나온다.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public PrometheusMeterRegistry meterRegistry(@Value("${spring.profiles.active:local}") String profile) {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        // 여러 환경의 메트릭이 한 Prometheus로 모일 때 구분할 수 있어야 한다.
        registry.config().commonTags("application", "aewol-backend", "environment", profile);

        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);

        return registry;
    }

    /**
     * HTTP 요청 소요 시간 필터.
     *
     * <p>빈으로 두는 이유는 {@code AewolApplication}이 톰캣을 띄우는 시점에 컨텍스트가 아직
     * refresh되기 전이라 {@code getBean}을 부를 수 없기 때문이다. 보안 필터와 같이
     * {@code DelegatingFilterProxy}가 나중에 이름으로 찾아간다.
     */
    @Bean
    public HttpMetricsFilter httpMetricsFilter(MeterRegistry registry) {
        return new HttpMetricsFilter(registry);
    }

}
