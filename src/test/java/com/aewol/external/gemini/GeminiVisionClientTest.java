package com.aewol.external.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// Note: Spring's MockRestRequestMatchers.requestTo()/method() require org.hamcrest on the
// classpath (not a project dependency), so URI/method checks below are custom RequestMatcher
// lambdas instead of those static helpers.

class GeminiVisionClientTest {

    private static final String ENDPOINT_PREFIX =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockRestServiceServer mockServer;
    private GeminiVisionClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.bindTo(restTemplate).build();
        client = new GeminiVisionClient(restTemplate);
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
    }

    private String geminiResponseWithText(String text) throws Exception {
        Map<String, Object> body = Map.of(
                "candidates", java.util.List.of(
                        Map.of("content", Map.of(
                                "parts", java.util.List.of(Map.of("text", text))
                        ))
                )
        );
        return objectMapper.writeValueAsString(body);
    }

    private RequestMatcher requestToUri(String expectedUri) {
        return request -> assertEquals(expectedUri, request.getURI().toString());
    }

    private RequestMatcher isPostMethod() {
        return request -> assertEquals(HttpMethod.POST, request.getMethod());
    }

    private RequestMatcher requestBodyMatchesImage(String expectedMimeType, byte[] expectedImageBytes) {
        return request -> {
            MockClientHttpRequest mockRequest = (MockClientHttpRequest) request;
            JsonNode body = objectMapper.readTree(mockRequest.getBodyAsString());
            JsonNode inlineData = body.path("contents").path(0).path("parts").path(1).path("inline_data");
            assertEquals(expectedMimeType, inlineData.path("mime_type").asText());
            assertEquals(Base64.getEncoder().encodeToString(expectedImageBytes), inlineData.path("data").asText());
            JsonNode textPart = body.path("contents").path(0).path("parts").path(0).path("text");
            assertEquals(true, textPart.asText().contains("hospital_name"));
        };
    }

    @Test
    @DisplayName("정상 응답이면 candidates[0].content.parts[0].text를 파싱해 반환하고 요청 바디에 이미지/프롬프트가 포함된다")
    void should_returnParsedJson_whenResponseIsValid() throws Exception {
        byte[] imageBytes = "dummy-image".getBytes(StandardCharsets.UTF_8);
        String extractedJson = "{\"hospital_name\":\"애월동물병원\",\"total_amount\":15000}";

        mockServer.expect(requestToUri(ENDPOINT_PREFIX + "?key=test-key"))
                .andExpect(isPostMethod())
                .andExpect(requestBodyMatchesImage("image/jpeg", imageBytes))
                .andRespond(withSuccess(geminiResponseWithText(extractedJson), MediaType.APPLICATION_JSON));

        String result = client.extractReceiptData(imageBytes, "image/jpeg");

        assertEquals(extractedJson, result);
        mockServer.verify();
    }

    @Test
    @DisplayName("응답 text가 코드펜스로 감싸져 있으면 제거하고 순수 JSON만 반환한다")
    void should_stripCodeFence_whenResponseWrappedInMarkdown() throws Exception {
        byte[] imageBytes = "dummy-image".getBytes(StandardCharsets.UTF_8);
        String extractedJson = "{\"hospital_name\":\"애월동물병원\"}";
        String wrapped = "```json\n" + extractedJson + "\n```";

        mockServer.expect(requestToUri(ENDPOINT_PREFIX + "?key=test-key"))
                .andRespond(withSuccess(geminiResponseWithText(wrapped), MediaType.APPLICATION_JSON));

        String result = client.extractReceiptData(imageBytes, "image/jpeg");

        assertEquals(extractedJson, result);
        mockServer.verify();
    }

    @Test
    @DisplayName("네트워크 오류가 발생하면 예외를 전파하지 않고 빈 JSON을 반환한다")
    void should_returnEmptyJson_whenNetworkErrorOccurs() {
        byte[] imageBytes = "dummy-image".getBytes(StandardCharsets.UTF_8);

        mockServer.expect(requestToUri(ENDPOINT_PREFIX + "?key=test-key"))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });

        String result = client.extractReceiptData(imageBytes, "image/jpeg");

        assertEquals("{}", result);
        mockServer.verify();
    }

    @Test
    @DisplayName("응답에 candidates가 없어 파싱할 수 없으면 빈 JSON을 반환한다")
    void should_returnEmptyJson_whenResponseCannotBeParsed() {
        byte[] imageBytes = "dummy-image".getBytes(StandardCharsets.UTF_8);

        mockServer.expect(requestToUri(ENDPOINT_PREFIX + "?key=test-key"))
                .andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));

        String result = client.extractReceiptData(imageBytes, "image/jpeg");

        assertEquals("{}", result);
        mockServer.verify();
    }

    @Test
    @DisplayName("응답 text가 JSON 객체가 아닌 배열이면 빈 JSON을 반환한다")
    void should_returnEmptyJson_whenResponseTextIsJsonArray() throws Exception {
        byte[] imageBytes = "dummy-image".getBytes(StandardCharsets.UTF_8);

        mockServer.expect(requestToUri(ENDPOINT_PREFIX + "?key=test-key"))
                .andRespond(withSuccess(geminiResponseWithText("[]"), MediaType.APPLICATION_JSON));

        String result = client.extractReceiptData(imageBytes, "image/jpeg");

        assertEquals("{}", result);
        mockServer.verify();
    }
}
