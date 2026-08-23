package com.aewol.config;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
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
 *
 * <p>밖에서 온 값은 형식을 확인하고 쓴다. 로그에 그대로 찍히고 응답 헤더로도 나가는
 * 값이라, 개행이 섞이면 로그를 위조하거나 헤더를 끼워 넣는 통로가 된다.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String MDC_KEY = "requestId";
    private static final String HEADER = "X-Request-Id";
    /** 밖에서 받은 값에 허용하는 형식. 개행·공백·제어문자를 모두 막는다. */
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // CloudFront나 로드밸런서가 이미 붙여 보낸 값이 있으면 그대로 쓴다. 그래야 앞단
        // 로그와 애플리케이션 로그가 같은 id로 이어진다.
        String requestId = request.getHeader(HEADER);
        if (requestId == null || !ALLOWED.matcher(requestId).matches()) {
            requestId = newRequestId();
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

    /**
     * 16자리 hex(64비트)를 쓴다.
     *
     * <p>앞서 8자만 쓰다가 늘렸다. 32비트로는 생일 문제 때문에 대략 8만 건 근처에서
     * 절반의 확률로 충돌한다. 서로 다른 요청이 같은 id를 갖게 되면 이 값을 넣은 이유인
     * "이 오류가 어느 요청에서 났는가"가 무너진다. 64비트면 현실적인 트래픽에서 충돌을
     * 걱정하지 않아도 되고, 로그 한 줄에 붙는 비용도 8자만큼만 늘어난다.
     */
    private static String newRequestId() {
        // toHexString은 앞자리 0을 버려 길이가 들쭉날쭉해진다. 로그 정렬을 위해 고정 폭으로 찍는다.
        return String.format("%016x", UUID.randomUUID().getMostSignificantBits());
    }
}
