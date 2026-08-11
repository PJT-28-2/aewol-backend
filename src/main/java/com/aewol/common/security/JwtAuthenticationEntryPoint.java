package com.aewol.common.security;

import com.aewol.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * 인증되지 않은 요청(토큰 없음/만료/무효)에 대해 표준 REST 의미인 401을 JSON으로 반환한다.
 * 기본 동작(Tomcat HTML 403)은 프론트의 토큰 재발급 트리거(401)와 어긋나므로 별도로 처리한다.
 */
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    // 이 프로젝트는 Spring Boot 자동설정을 쓰지 않아 ObjectMapper 빈이 없으므로 직접 생성한다.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        OBJECT_MAPPER.writeValue(response.getWriter(),
                ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), "로그인이 필요합니다."));
    }
}
