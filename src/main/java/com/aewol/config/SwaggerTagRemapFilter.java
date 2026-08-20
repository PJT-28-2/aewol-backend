package com.aewol.config;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RestController;
import springfox.documentation.oas.web.OpenApiTransformationContext;
import springfox.documentation.oas.web.WebMvcOpenApiTransformationFilter;
import springfox.documentation.spi.DocumentationType;

/**
 * springfox는 {@code @Tag(name=...)}를 최상위 tags 목록에는 반영하지만, 개별 operation에는
 * 컨트롤러 클래스명 기반 기본 태그(donation-controller)를 붙인다. 그 결과 Swagger UI에
 * 빈 그룹과 실제 그룹이 이중으로 표시된다. 이 필터가 operation의 기본 태그를
 * {@code @Tag} 이름으로 되돌려 준다.
 */
@Component
@Profile("!prod")
public class SwaggerTagRemapFilter implements WebMvcOpenApiTransformationFilter {

    private final Map<String, String> defaultTagToDeclaredTag = new HashMap<>();

    public SwaggerTagRemapFilter(ApplicationContext applicationContext) {
        applicationContext.getBeansWithAnnotation(RestController.class)
                .values()
                .forEach(bean -> {
                    Class<?> type = AopUtils.getTargetClass(bean);
                    Tag tag = AnnotationUtils.findAnnotation(type, Tag.class);
                    if (tag != null) {
                        defaultTagToDeclaredTag.put(normalize(type.getSimpleName()), tag.name());
                    }
                });
    }

    /** "gov-24-sync-controller"와 "Gov24SyncController"를 같은 키로 맞춘다. */
    private static String normalize(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    @Override
    public OpenAPI transform(OpenApiTransformationContext<HttpServletRequest> context) {
        OpenAPI openApi = context.getSpecification();
        if (openApi.getPaths() == null) {
            return openApi;
        }
        openApi.getPaths().values().forEach(pathItem ->
                pathItem.readOperations().forEach(operation -> {
                    List<String> tags = operation.getTags();
                    if (tags == null) {
                        return;
                    }
                    operation.setTags(tags.stream()
                            .map(tag -> defaultTagToDeclaredTag.getOrDefault(normalize(tag), tag))
                            .distinct()
                            .collect(Collectors.toList()));
                }));
        return openApi;
    }

    @Override
    public boolean supports(DocumentationType documentationType) {
        return DocumentationType.OAS_30.equals(documentationType);
    }
}
