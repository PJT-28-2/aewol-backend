package com.aewol.external.paddleocr;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// Note: 같은 이유(hamcrest 미의존)로 GeminiVisionClientTest와 동일하게 커스텀
// RequestMatcher 람다를 사용한다.

class PaddleOcrClientTest {

    private static final String ENDPOINT = "http://localhost:8000/extract-receipt";

    private MockRestServiceServer mockServer;
    private PaddleOcrClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.bindTo(restTemplate).build();
        client = new PaddleOcrClient(restTemplate);
        ReflectionTestUtils.setField(client, "ocrServiceUrl", "http://localhost:8000");
    }

    private RequestMatcher requestToUri(String expectedUri) {
        return request -> assertEquals(expectedUri, request.getURI().toString());
    }

    private RequestMatcher isPostMethod() {
        return request -> assertEquals(HttpMethod.POST, request.getMethod());
    }

    private RequestMatcher isMultipartWithImageBytes(byte[] expectedImageBytes) {
        return request -> {
            MockClientHttpRequest mockRequest = (MockClientHttpRequest) request;
            assertTrue(mockRequest.getHeaders().getContentType().toString().startsWith("multipart/form-data"));
            String body = mockRequest.getBodyAsString(StandardCharsets.ISO_8859_1);
            assertTrue(body.contains(new String(expectedImageBytes, StandardCharsets.ISO_8859_1)));
            assertTrue(body.contains("name=\"image\""));
        };
    }

    @Test
    @DisplayName("정상 응답이면 ocr-service의 JSON 응답을 그대로 반환하고 요청은 image 파트를 담은 멀티파트다")
    void should_returnResponseBody_whenResponseIsValidJsonObject() {
        byte[] imageBytes = "dummy-image".getBytes(StandardCharsets.UTF_8);
        String extractedJson = "{\"hospital_name\":\"애월동물병원\",\"total_amount\":15000.0}";

        mockServer.expect(requestToUri(ENDPOINT))
                .andExpect(isPostMethod())
                .andExpect(isMultipartWithImageBytes(imageBytes))
                .andRespond(withSuccess(extractedJson, MediaType.APPLICATION_JSON));

        String result = client.extractReceiptData(imageBytes, "image/jpeg");

        assertEquals(extractedJson, result);
        mockServer.verify();
    }

    @Test
    @DisplayName("네트워크 오류가 발생하면 예외를 전파하지 않고 빈 JSON을 반환한다")
    void should_returnEmptyJson_whenNetworkErrorOccurs() {
        byte[] imageBytes = "dummy-image".getBytes(StandardCharsets.UTF_8);

        mockServer.expect(requestToUri(ENDPOINT))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });

        String result = client.extractReceiptData(imageBytes, "image/jpeg");

        assertEquals("{}", result);
        mockServer.verify();
    }

    @Test
    @DisplayName("응답이 JSON 객체가 아니면(배열 등) 빈 JSON을 반환한다")
    void should_returnEmptyJson_whenResponseIsNotJsonObject() {
        byte[] imageBytes = "dummy-image".getBytes(StandardCharsets.UTF_8);

        mockServer.expect(requestToUri(ENDPOINT))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        String result = client.extractReceiptData(imageBytes, "image/jpeg");

        assertEquals("{}", result);
        mockServer.verify();
    }
}
