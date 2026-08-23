package com.aewol.config;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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

    /**
     * 커넥션 풀 메트릭을 붙인다.
     *
     * <p>{@code DataSource}가 만들어진 뒤에 등록해야 하므로 별도 빈으로 둔다. Hikari는
     * {@code setMetricsTrackerFactory} 대신 {@code setMetricRegistry}로 Micrometer를 받는다.
     */
    @Bean
    public HikariMetricsBinder hikariMetricsBinder(DataSource dataSource, MeterRegistry registry) {
        return new HikariMetricsBinder(dataSource, registry);
    }

    /** 등록 시점을 명시하려고 만든 얇은 래퍼. 빈 초기화 순서를 코드로 드러낸다. */
    public static class HikariMetricsBinder {
        public HikariMetricsBinder(DataSource dataSource, MeterRegistry registry) {
            if (dataSource instanceof HikariDataSource) {
                ((HikariDataSource) dataSource).setMetricRegistry(registry);
                log.info("[METRICS] HikariCP 메트릭을 등록했습니다.");
            } else {
                // 테스트에서 다른 DataSource를 쓰는 경우가 있다. 메트릭이 없다고 기동을 막지 않는다.
                log.warn("[METRICS] HikariDataSource가 아니라 커넥션 풀 메트릭을 건너뜁니다. type={}",
                        dataSource.getClass().getName());
            }
        }
    }
}
