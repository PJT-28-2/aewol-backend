package com.aewol.config;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

@Configuration
@EnableBatchProcessing
public class BatchConfig {

    /**
     * Spring Batch 메타데이터 테이블 자동 생성.
     * 이미 존재하는 경우 오류 무시.
     */
    @Bean
    public DataSourceInitializer batchSchemaInitializer(DataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("org/springframework/batch/core/schema-mysql.sql"));
        populator.setContinueOnError(true);

        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(populator);
        return initializer;
    }
}
