package com.aewol.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aewol.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void should_returnMethodNotAllowed_when_httpMethodIsNotSupported() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodNotSupportedException(
                new HttpRequestMethodNotSupportedException("POST"));

        assertEquals(405, response.getStatusCodeValue());
        assertEquals(405, response.getBody().getStatus());
        assertEquals("지원하지 않는 HTTP 메서드입니다.", response.getBody().getMessage());
    }
}
