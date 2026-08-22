package com.aewol.config;

import java.io.IOException;
import java.util.UUID;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 추적 id를 붙인다.
 *
 * <p>이 값이 없으면 동시 요청의 로그가 뒤섞여 "이 오류가 어느 요청에서 났는지"를 되짚을
 * 수 없다. 장애 조사에서 가장 먼저 필요한 것이 그 연결이다.
 *
 * <p>이 프로젝트는 Spring Boot가 아니라 필터 자동 등록이 없다. AewolApplication이
 * 보안 필터보다 먼저 등록한다 — 인증에서 튕기는 요청도 같은 id로 묶여야 하기 때문이다.
 *
 * <p>응답 헤더로도 내보낸다. 사용자가 오류를 신고할 때 이 값을 알려주면 로그에서 바로
 * 찾을 수 있다.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String MDC_KEY = "requestId";
    private static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // CloudFront나 로드밸런서가 이미 붙여 보낸 값이 있으면 그대로 쓴다. 그래야 앞단
        // 로그와 애플리케이션 로그가 같은 id로 이어진다.
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 스레드는 재사용된다. 지우지 않으면 다음 요청 로그에 남의 id가 붙는다.
            MDC.remove(MDC_KEY);
        }
    }
}
