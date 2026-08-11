package com.aewol.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringJUnitWebConfig(classes = AppConfig.class)
class SwaggerConfigTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("OpenAPI 문서는 실제 컨트롤러 경로와 선언한 태그를 포함한다")
    void should_exposeOpenApiDocument_withDeclaredControllerTag() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        JsonNode document = objectMapper.readTree(body);
        JsonNode loginOperation = document.at("/paths/~1api~1auth~1login/post");

        assertEquals("3.0.3", document.path("openapi").asText());
        assertEquals("애월 (AeWol) API", document.at("/info/title").asText());
        assertFalse(loginOperation.isMissingNode());
        assertEquals("Auth", loginOperation.at("/tags/0").asText());
    }

    @Test
    @DisplayName("Swagger UI 정적 리소스를 직접 제공한다")
    void should_serveSwaggerUiIndex() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    @DisplayName("Swagger UI는 태그와 API를 이름순으로 정렬한다")
    void should_sortSwaggerUiTagsAndOperationsAlphabetically() throws Exception {
        String body = mockMvc.perform(get("/swagger-resources/configuration/ui"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        JsonNode configuration = objectMapper.readTree(body);

        assertEquals("alpha", configuration.path("operationsSorter").asText());
        assertEquals("alpha", configuration.path("tagsSorter").asText());
    }
}
