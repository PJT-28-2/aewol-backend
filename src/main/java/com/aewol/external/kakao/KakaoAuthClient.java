package com.aewol.external.kakao;

import com.aewol.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoAuthClient {

    private final RestTemplate restTemplate;

    @Value("${external.kakao.client-id:}")
    private String clientId;

    @Value("${external.kakao.client-secret:}")
    private String clientSecret;

    @Value("${external.kakao.redirect-uri:}")
    private String redirectUri;

    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String PROFILE_URL = "https://kapi.kakao.com/v2/user/me";
    private static final String KAKAO_LOGIN_FAILED_MESSAGE = "카카오 로그인에 실패했습니다.";
    private static final String KAKAO_UNAVAILABLE_MESSAGE =
            "카카오 로그인 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해주세요.";

    public String getAccessToken(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("client_id", clientId);
        if (StringUtils.hasText(clientSecret)) {
            params.add("client_secret", clientSecret);
        }
        params.add("redirect_uri", redirectUri);
        params.add("code", code);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
        ResponseEntity<Map> response = executeKakaoRequest(
                () -> restTemplate.postForEntity(TOKEN_URL, request, Map.class));
        Map<String, Object> body = response.getBody();
        Object accessToken = body != null ? body.get("access_token") : null;
        if (!(accessToken instanceof String) || !StringUtils.hasText((String) accessToken)) {
            throw invalidKakaoResponse();
        }
        return ((String) accessToken).trim();
    }

    public KakaoUserInfo getUserInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<Map> response = executeKakaoRequest(
                () -> restTemplate.exchange(PROFILE_URL, HttpMethod.GET, request, Map.class));
        return toUserInfo(response.getBody());
    }

    private ResponseEntity<Map> executeKakaoRequest(Supplier<ResponseEntity<Map>> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException e) {
            throw BusinessException.unauthorized(KAKAO_LOGIN_FAILED_MESSAGE);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, KAKAO_UNAVAILABLE_MESSAGE);
        } catch (RestClientException e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, KAKAO_UNAVAILABLE_MESSAGE);
        }
    }

    private KakaoUserInfo toUserInfo(Map<String, Object> response) {
        if (response == null) {
            throw invalidKakaoResponse();
        }

        String providerId = requiredProviderId(response.get("id"));
        Object accountValue = response.get("kakao_account");
        if (!(accountValue instanceof Map)) {
            throw invalidKakaoResponse();
        }

        Map<?, ?> kakaoAccount = (Map<?, ?>) accountValue;
        String name = requiredAccountName(kakaoAccount.get("name"));
        String email = optionalText(kakaoAccount.get("email"));
        if (email == null) {
            email = providerId + "@kakao.user";
        }
        return new KakaoUserInfo(providerId, email, name);
    }

    private String requiredProviderId(Object value) {
        if (value == null || (!(value instanceof String) && !(value instanceof Number))) {
            throw invalidKakaoResponse();
        }
        String normalized = value.toString().trim();
        if (normalized.isEmpty()) {
            throw invalidKakaoResponse();
        }
        return normalized;
    }

    private String requiredAccountName(Object value) {
        if (!(value instanceof String)) {
            throw invalidKakaoResponse();
        }
        String normalized = ((String) value).trim();
        if (normalized.isEmpty()) {
            throw invalidKakaoResponse();
        }
        return normalized;
    }

    private String optionalText(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw invalidKakaoResponse();
        }
        String normalized = ((String) value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private BusinessException invalidKakaoResponse() {
        return new BusinessException(HttpStatus.BAD_GATEWAY,
                "카카오 사용자 정보를 확인할 수 없습니다.");
    }
}
