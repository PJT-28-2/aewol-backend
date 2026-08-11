package com.aewol.common.security;

import com.aewol.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 인증은 됐지만 권한이 부족한 요청(예: USER 전용 엔드포인트에 접근)에 대해 403을 JSON으로 반환한다.
 * 미인증(401)과 구분해, 프론트가 403은 재발급이 아닌 실제 권한 오류로 처리할 수 있게 한다.
 */
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    // 이 프로젝트는 Spring Boot 자동설정을 쓰지 않아 ObjectMapper 빈이 없으므로 직접 생성한다.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        OBJECT_MAPPER.writeValue(response.getWriter(),
                ApiResponse.error(HttpStatus.FORBIDDEN.value(), "접근 권한이 없습니다."));
    }
}
