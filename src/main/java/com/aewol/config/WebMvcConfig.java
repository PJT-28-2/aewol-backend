package com.aewol.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.TimeZone;

@Configuration
@EnableWebMvc
public class WebMvcConfig implements WebMvcConfigurer {

    private final String uploadDir;

    public WebMvcConfig(Environment environment) {
        this.uploadDir = environment.getProperty("file.upload-dir", "./uploads");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 공동구매 상품 이미지만 공개 정적 리소스다. 그 외 업로드 파일은
        // FileController의 만료 가능한 서명 URL을 통해서만 조회한다.
        String publicImageLocation = Path.of(uploadDir, "group-purchase").toUri().toString();
        if (!publicImageLocation.endsWith("/")) {
            publicImageLocation += "/";
        }
        registry.addResourceHandler("/uploads/group-purchase/**")
                .addResourceLocations(publicImageLocation);
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.stream()
                .filter(c -> c instanceof MappingJackson2HttpMessageConverter)
                .map(c -> (MappingJackson2HttpMessageConverter) c)
                .forEach(c -> c.getObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .setTimeZone(TimeZone.getTimeZone("Asia/Seoul")));
    }

    @Bean
    public StandardServletMultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver();
    }

    /** @Validated가 붙은 컨트롤러의 @RequestParam/@PathVariable 단위 제약조건(@NotBlank, @Min 등)을 검증한다 */
    @Bean
    public MethodValidationPostProcessor methodValidationPostProcessor() {
        return new MethodValidationPostProcessor();
    }
}
