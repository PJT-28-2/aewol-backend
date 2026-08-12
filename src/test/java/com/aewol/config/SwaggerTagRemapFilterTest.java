package com.aewol.config;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.swagger.v3.oas.models.OpenAPI;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RestController;
import springfox.documentation.oas.web.OpenApiTransformationContext;

class SwaggerTagRemapFilterTest {

    @Test
    @DisplayName("문서화할 API 경로가 없어도 빈 OpenAPI 스펙을 그대로 반환한다")
    @SuppressWarnings("unchecked")
    void should_returnEmptySpecification_whenPathsAreMissing() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBeansWithAnnotation(RestController.class)).thenReturn(Map.of());
        SwaggerTagRemapFilter filter = new SwaggerTagRemapFilter(applicationContext);
        OpenAPI specification = new OpenAPI();
        OpenApiTransformationContext<HttpServletRequest> context = mock(OpenApiTransformationContext.class);
        when(context.getSpecification()).thenReturn(specification);

        OpenAPI result = filter.transform(context);

        assertSame(specification, result);
    }
}
