package com.aewol.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.PropertySources;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 루트 애플리케이션 설정.
 * Spring Boot 없이 YAML 프로퍼티를 로드하고 컴포넌트 스캔을 수행합니다.
 */
@Configuration
@ComponentScan(basePackages = "com.aewol")
@PropertySources({
    @PropertySource(
        value = "classpath:application.yml",
        factory = YamlPropertySourceFactory.class
    ),
    @PropertySource(
        value = "classpath:application-local.yml",
        factory = YamlPropertySourceFactory.class,
        ignoreResourceNotFound = true
    ),
    @PropertySource(
        value = "classpath:application-dev.yml",
        factory = YamlPropertySourceFactory.class,
        ignoreResourceNotFound = true
    )
})
@EnableScheduling
public class AppConfig {
}
