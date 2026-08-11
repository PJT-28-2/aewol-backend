package com.aewol.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

class JwtAuthenticationEntryPointTest {

    private final JwtAuthenticationEntryPoint entryPoint = new JwtAuthenticationEntryPoint();

    @Test
    void should_write401JsonBody_when_unauthenticated() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest(), response,
                new InsufficientAuthenticationException("no auth"));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().contains("application/json"));

        String body = response.getContentAsString();
        assertTrue(body.contains("\"status\":401"), body);
        assertTrue(body.contains("로그인이 필요합니다."), body);
        assertTrue(body.contains("\"result\":null"), body);
    }
}
