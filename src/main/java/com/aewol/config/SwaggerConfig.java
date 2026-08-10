package com.aewol.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.oas.annotations.EnableOpenApi;
import springfox.documentation.service.AuthorizationScope;
import springfox.documentation.service.HttpAuthenticationScheme;
import springfox.documentation.service.SecurityReference;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spi.service.contexts.SecurityContext;
import springfox.documentation.spring.web.plugins.Docket;

/**
 * Swagger(OpenAPI 3) 문서 설정.
 *
 * <p>이 프로젝트는 Spring Boot가 아니므로 springdoc-openapi를 쓸 수 없다.
 * 순수 Spring MVC를 지원하는 springfox로 컨트롤러의
 * {@code io.swagger.v3.oas.annotations} 애노테이션을 스캔해 스펙을 생성한다.
 *
 * <ul>
 *   <li>스펙: {@code /v3/api-docs}
 *   <li>UI: {@code /swagger-ui/index.html}
 * </ul>
 */
@Configuration
@EnableOpenApi
public class SwaggerConfig {

    private static final String SECURITY_SCHEME_NAME = "Bearer Authentication";

    @Bean
    public Docket aewolApiDocket() {
        return new Docket(DocumentationType.OAS_30)
                .apiInfo(new ApiInfoBuilder()
                        .title("애월 (AeWol) API")
                        .description("반려동물 전용 전자지갑 서비스 API")
                        .version("v1.0.0")
                        .build())
                .securitySchemes(List.of(HttpAuthenticationScheme.JWT_BEARER_BUILDER
                        .name(SECURITY_SCHEME_NAME)
                        .build()))
                .securityContexts(List.of(SecurityContext.builder()
                        .securityReferences(List.of(new SecurityReference(
                                SECURITY_SCHEME_NAME,
                                new AuthorizationScope[] {
                                        new AuthorizationScope("global", "accessEverything")
                                })))
                        .build()))
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.aewol.domain"))
                .paths(PathSelectors.any())
                .build();
    }
}
