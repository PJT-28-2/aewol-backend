package com.aewol.domain.auth.support;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.exception.ErrorCode;
import java.time.Duration;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 카카오 가입 registrationToken을 HttpOnly 쿠키로 한 번 더 묶는다.
 *
 * <p>토큰은 SPA가 전화번호 인증·가입 완료 API에 실어 보내야 해서 응답 JSON에도 남긴다.
 * JSON만 있으면 XSS로 탈취한 값으로 피해자 카카오 providerId를 선점할 수 있다.
 * 쿠키는 JS가 읽지 못하므로, 쿠키와 바디 토큰이 같을 때만 가입 단계를 진행한다.
 */
@Component
public class KakaoRegistrationCookie {

    public static final String NAME = "kakao_reg";
    public static final String PATH = "/api/auth/oauth/kakao";
    static final Duration MAX_AGE = Duration.ofSeconds(900);

    private final boolean secure;

    public KakaoRegistrationCookie(@Value("${cookie.secure:false}") boolean secure) {
        this.secure = secure;
    }

    public void write(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(token, MAX_AGE).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
    }

    public void requireMatches(HttpServletRequest request, String registrationToken) {
        if (!StringUtils.hasText(registrationToken) || !registrationToken.equals(read(request))) {
            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "카카오 가입 세션이 유효하지 않습니다.",
                    ErrorCode.KAKAO_REGISTRATION_SESSION_INVALID_OR_EXPIRED);
        }
    }

    private String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .path(PATH)
                .maxAge(maxAge)
                .sameSite("Lax")
                .build();
    }
}
