package com.aewol.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import javax.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private MockHttpServletResponse run(String incoming) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (incoming != null) {
            request.addHeader("X-Request-Id", incoming);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        return response;
    }

    // 앞단(CloudFront·LB)이 붙인 값을 그대로 이어야 로그가 한 줄로 연결된다.
    @Test
    @DisplayName("정상적인 값이 오면 그대로 쓴다")
    void should_reuseIncomingId_when_wellFormed() throws Exception {
        assertEquals("abc-123_XYZ", run("abc-123_XYZ").getHeader("X-Request-Id"));
    }

    /*
     * 이 값은 로그에 그대로 찍히고 응답 헤더로도 나간다. 개행이 섞이면 없던 로그 줄을
     * 지어내거나(로그 위조) 헤더를 끼워 넣는 통로가 된다. 앞서 길이만 확인하고 있었다.
     */
    @Test
    @DisplayName("개행이 섞인 값은 쓰지 않는다")
    void should_rejectIncomingId_when_containsNewline() throws Exception {
        String forged = "abc\n2026-01-01 00:00:00.000 ERROR [x] 지어낸 로그";

        String used = run(forged).getHeader("X-Request-Id");

        assertNotEquals(forged, used);
        assertFalse(used.contains("\n"));
        assertFalse(used.contains("\r"));
    }

    @Test
    @DisplayName("형식에 맞지 않는 값은 새로 만든다")
    void should_generateNewId_when_malformed() throws Exception {
        assertEquals(16, run("공백 있는 값").getHeader("X-Request-Id").length());
        assertEquals(16, run("").getHeader("X-Request-Id").length());
        assertEquals(16, run("x".repeat(65)).getHeader("X-Request-Id").length());
        assertEquals(16, run(null).getHeader("X-Request-Id").length());
    }

    /*
     * 8자(32비트)로는 생일 문제 때문에 대략 8만 건 근처에서 절반의 확률로 충돌한다.
     * 서로 다른 요청이 같은 id를 가지면 이 값을 넣은 이유가 무너진다.
     */
    @Test
    @DisplayName("만들어낸 id는 64비트를 채운다")
    void should_useEnoughEntropy_when_generated() throws Exception {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String id = run(null).getHeader("X-Request-Id");
            assertTrue(id.matches("[0-9a-f]{16}"), id);
            seen.add(id);
        }
        assertEquals(1000, seen.size());
    }

    // 스레드는 재사용된다. 지우지 않으면 다음 요청 로그에 남의 id가 붙는다.
    @Test
    @DisplayName("요청이 끝나면 MDC를 비운다")
    void should_clearMdc_when_requestEnds() throws Exception {
        run("abc-123");

        assertNull(MDC.get("requestId"));
    }
}
