package com.aewol.config;

import java.util.Objects;
import java.util.Properties;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

/**
 * Tomcat의 {@link javax.servlet.MultipartConfigElement}에 넘길 파일 업로드 제한값을
 * application.yml에서 직접 읽어온다.
 *
 * <p>이 프로젝트는 Spring Boot가 아니라서 {@code AewolApplication.main()}이 Tomcat을
 * 직접 구성하는 시점에는 아직 Spring 컨텍스트가 refresh되지 않아 {@code @Value}로
 * {@code spring.servlet.multipart.*} 값을 주입받을 수 없다. 그렇다고 숫자를 그대로
 * 하드코딩해두면 application.yml 값과 실제 적용값이 따로 놀 수 있다(PR #197 리뷰) —
 * 실제로 max-request-size가 10MB로 하드코딩돼 있어 문서화된 35MB와 어긋났던 적이 있다.
 *
 * <p>그래서 숫자를 복제하는 대신 이 클래스가 application.yml을 직접(YamlPropertiesFactoryBean
 * 으로, Spring 컨텍스트 없이) 읽어 단일 진실 공급원으로 삼는다. 값을 바꾸려면 이제
 * application.yml만 고치면 된다.
 */
public final class MultipartLimits {

    private static final String YML_PATH = "application.yml";
    private static final String MAX_FILE_SIZE_KEY = "spring.servlet.multipart.max-file-size";
    private static final String MAX_REQUEST_SIZE_KEY = "spring.servlet.multipart.max-request-size";

    private MultipartLimits() {
    }

    public static long maxFileSizeBytes() {
        return readDataSize(MAX_FILE_SIZE_KEY).toBytes();
    }

    public static long maxRequestSizeBytes() {
        return readDataSize(MAX_REQUEST_SIZE_KEY).toBytes();
    }

    private static DataSize readDataSize(String key) {
        Properties properties = loadYaml();
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(YML_PATH + "에 " + key + " 설정이 없습니다.");
        }
        return DataSize.parse(value);
    }

    private static Properties loadYaml() {
        try {
            YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
            factory.setResources(new ClassPathResource(YML_PATH));
            return Objects.requireNonNull(factory.getObject());
        } catch (Exception e) {
            throw new IllegalStateException(YML_PATH + "을 읽지 못했습니다.", e);
        }
    }
}
