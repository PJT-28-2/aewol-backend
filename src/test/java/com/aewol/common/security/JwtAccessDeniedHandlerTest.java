package com.aewol.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

class JwtAccessDeniedHandlerTest {

    private final JwtAccessDeniedHandler handler = new JwtAccessDeniedHandler();

    @Test
    void should_write403JsonBody_when_accessDenied() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(new MockHttpServletRequest(), response,
                new AccessDeniedException("denied"));

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentType().contains("application/json"));

        String body = response.getContentAsString();
        assertTrue(body.contains("\"status\":403"), body);
        assertTrue(body.contains("접근 권한이 없습니다."), body);
        assertTrue(body.contains("\"result\":null"), body);
    }
}
