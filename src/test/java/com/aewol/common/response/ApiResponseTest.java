package com.aewol.common.response;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.exception.ErrorCode;
import com.aewol.common.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void successResponseUsesResultField() throws Exception {
        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsString(ApiResponse.success(Collections.singletonMap("id", 1))));

        assertEquals(200, json.get("status").asInt());
        assertEquals("success", json.get("message").asText());
        assertEquals(1, json.get("result").get("id").asInt());
        assertFalse(json.has("data"));
        assertFalse(json.has("errorCode"));
    }

    @Test
    void responseIncludesResultWhenItIsNull() throws Exception {
        ApiResponse<Void> response = ApiResponse.success();
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertTrue(json.has("result"));
        assertTrue(json.get("result").isNull());
        assertNull(response.getResult());
    }

    @Test
    void acceptedResponseCarriesStatusAndMessageWithoutPayload() throws Exception {
        ApiResponse<Void> response = ApiResponse.accepted("시딩을 시작했습니다.");
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertEquals(202, json.get("status").asInt());
        assertEquals("시딩을 시작했습니다.", json.get("message").asText());
        assertTrue(json.has("result"));
        assertTrue(json.get("result").isNull());
        assertNull(response.getResult());
    }

    @Test
    void businessExceptionResponseUsesSameContract() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<ApiResponse<Void>> entity = handler.handleBusinessException(
                BusinessException.notFound("member not found"));
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(entity.getBody()));

        assertEquals(404, entity.getStatusCodeValue());
        assertEquals(404, json.get("status").asInt());
        assertEquals("member not found", json.get("message").asText());
        assertTrue(json.has("result"));
        assertTrue(json.get("result").isNull());
        assertFalse(json.has("data"));
        assertFalse(json.has("errorCode"));
    }

    @Test
    void codedBusinessExceptionResponseIncludesMachineReadableErrorCode() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<ApiResponse<Void>> entity = handler.handleBusinessException(
                new BusinessException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "invalid registration session",
                        ErrorCode.KAKAO_REGISTRATION_SESSION_INVALID_OR_EXPIRED));
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(entity.getBody()));

        assertEquals(400, entity.getStatusCodeValue());
        assertEquals(400, json.get("status").asInt());
        assertEquals("invalid registration session", json.get("message").asText());
        assertTrue(json.get("result").isNull());
        assertEquals("KAKAO_REGISTRATION_SESSION_INVALID_OR_EXPIRED",
                json.get("errorCode").asText());
    }
}
