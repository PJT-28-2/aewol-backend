package com.aewol.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 잘못된 요청은 클라이언트 잘못이므로 4xx로 응답해야 한다.
 *
 * <p>핸들러가 없으면 전부 {@code @ExceptionHandler(Exception.class)}로 떨어져 500이 나가고,
 * 프론트가 "입력 오류"와 "서버 장애"를 구분하지 못한다. 로그에도 가짜 500이 쌓여
 * 진짜 장애가 묻힌다(#130).
 */
class GlobalExceptionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("필수 요청 파라미터가 없으면 400과 파라미터명을 돌려준다")
    void should_return400_when_requiredParameterIsMissing() throws Exception {
        JsonNode body = call(get("/probe/param"), 400);

        assertEquals(400, body.get("status").asInt());
        assertEquals("petId 값이 필요해요", body.get("message").asText());
    }

    @Test
    @DisplayName("파라미터를 채우면 정상 응답한다")
    void should_return200_when_requiredParameterIsPresent() throws Exception {
        MvcResult result = mockMvc.perform(get("/probe/param").param("petId", "9001")).andReturn();

        assertEquals(200, result.getResponse().getStatus());
    }

    @Test
    @DisplayName("본문 JSON이 깨졌으면 400을 돌려준다")
    void should_return400_when_requestBodyIsMalformed() throws Exception {
        JsonNode body = call(post("/probe/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{깨진 json"), 400);

        assertEquals(400, body.get("status").asInt());
        assertEquals("요청 본문을 읽을 수 없어요", body.get("message").asText());
    }

    @Test
    @DisplayName("본문이 비었으면 400을 돌려준다")
    void should_return400_when_requestBodyIsEmpty() throws Exception {
        JsonNode body = call(post("/probe/body").contentType(MediaType.APPLICATION_JSON), 400);

        assertEquals(400, body.get("status").asInt());
    }

    @Test
    @DisplayName("파싱 예외 메시지를 그대로 노출하지 않는다")
    void should_hideParserDetail_when_requestBodyIsMalformed() throws Exception {
        JsonNode body = call(post("/probe/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{깨진 json"), 400);

        // 잭슨 파서의 클래스명·오프셋이 새어나가면 내부 구조를 알려주는 셈이다
        String message = body.get("message").asText();
        assertFalse(message.contains("com.fasterxml"), "파서 클래스명이 노출됐다: " + message);
        assertFalse(message.contains("line:"), "파서 위치정보가 노출됐다: " + message);
    }

    @Test
    @DisplayName("필수 multipart 파일이 없으면 400과 파트명을 돌려준다")
    void should_return400_when_requiredPartIsMissing() throws Exception {
        JsonNode body = call(multipart("/probe/part"), 400);

        assertEquals(400, body.get("status").asInt());
        assertEquals("photo 파일이 필요해요", body.get("message").asText());
    }

    @Test
    @DisplayName("지원하지 않는 HTTP 메서드는 405를 돌려준다")
    void should_return405_when_methodIsNotSupported() throws Exception {
        JsonNode body = call(post("/probe/param"), 405);

        assertEquals(405, body.get("status").asInt());
        assertEquals("POST 메서드는 지원하지 않아요", body.get("message").asText());
    }

    @Test
    @DisplayName("Content-Type이 맞지 않으면 415를 돌려준다")
    void should_return415_when_contentTypeIsNotSupported() throws Exception {
        // 파일 업로드 엔드포인트에 JSON을 보내는 상황
        JsonNode body = call(post("/probe/part")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"), 415);

        assertEquals(415, body.get("status").asInt());
        assertEquals("지원하지 않는 형식의 요청이에요", body.get("message").asText());
    }

    private JsonNode call(RequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus());
        result.getResponse().setCharacterEncoding(StandardCharsets.UTF_8.name());
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/param")
        String param(@RequestParam String petId) {
            return petId;
        }

        @PostMapping("/probe/body")
        String body(@RequestBody Payload payload) {
            return payload.name;
        }

        // consumes를 명시해야 실제 업로드 엔드포인트처럼 Content-Type 불일치가
        // 매핑 단계에서 걸린다.
        @PostMapping(value = "/probe/part", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        String part(@RequestPart("photo") MultipartFile photo) {
            return photo.getOriginalFilename();
        }
    }

    static class Payload {
        public String name;
    }
}
