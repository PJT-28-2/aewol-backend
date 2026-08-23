package com.aewol.config;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

class DataSourceMetricsConfigTest {

    @Test
    @DisplayName("Hikari 풀이 시작되기 전에 메트릭 레지스트리를 연결한다")
    void should_bindMetricsRegistry_beforeStartingHikariPool() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context,
                    "spring.datasource.url=jdbc:h2:mem:metrics-wiring;MODE=MySQL;DB_CLOSE_DELAY=-1",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.profiles.active=test");
            context.register(DataSourceConfig.class, MetricsConfig.class);
            context.refresh();

            DataSource dataSource = context.getBean(DataSource.class);
            PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
            HikariDataSource hikari = (HikariDataSource) dataSource;

            assertSame(registry, hikari.getMetricRegistry());
            try (Connection ignored = hikari.getConnection()) {
                assertTrue(hikari.isRunning());
            }
            assertTrue(registry.getMeters().stream()
                    .anyMatch(meter -> meter.getId().getName().startsWith("hikaricp.connections")));
        }
    }
}
