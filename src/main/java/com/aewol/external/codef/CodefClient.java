package com.aewol.external.codef;

import com.aewol.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class CodefClient {

    private final RestTemplate restTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${external.codef.client-id:}")
    private String clientId;

    @Value("${external.codef.client-secret:}")
    private String clientSecret;

    @Value("${external.codef.oauth-url:https://oauth.codef.io/oauth/token}")
    private String oauthUrl;

    // 데모 서버 기본값(1일 100건 무료). 정식 계약 후 application-*.yml에서
    // https://api.codef.io 로 교체.
    @Value("${external.codef.api-base-url:https://development.codef.io}")
    private String apiBaseUrl;

    private static final String TOKEN_REDIS_KEY = "codef:accessToken";
    // CODEF accessToken은 1주일 유효 — 만료 임박 재요청을 피하려고 6일만 캐싱
    private static final long TOKEN_TTL_DAYS = 6;

    // CODEF 입금자명은 4자리로 고정한다(2026-08-06 결정). inPrintType="1"(랜덤 한글 단어)은
    // 길이가 4~5자로 들쭉날쭉해서(예: "하얀코끼리" 5자) inPrintType="0"(4자리 랜덤 숫자,
    // 예: "5673")으로 바꿨다 — CODEF가 생성한다는 원칙(자체 고정값 방식 폐기)은 그대로 유지된다.
    private static final int DEPOSITOR_NAME_LENGTH = 4;

    /**
     * CODEF 계좌 인증(1원 이체) 요청.
     * bankCode는 bank_master의 금융결제원 3자리 코드 — CODEF organization(4자리)으로
     * 패딩해서 보낸다("0" + bankCode, 예: "004" -> "0004").
     * inPrintType=0(4자리 랜덤 숫자, 예: "5673")으로 요청해서 CODEF가 직접 생성한 입금자명을
     * 그대로 받아쓴다 — 우리가 미리 값을 만들어 보내지 않는다. 길이가 항상 4자로 고정되므로
     * 프론트(AccountAuthOneWon.vue)는 고정 4칸 입력 UI를 그대로 쓸 수 있다.
     *
     * TODO: 데모 서버는 실제 인증코드가 아니라 랜덤 성공/실패 테스트 데이터를
     * 반환한다(CODEF 문서 명시) — 정식 버전 전환 전까지는 이 검증이 항상
     * 신뢰할 수 있는 건 아니라는 점 감안 필요.
     *
     * @return CODEF가 생성한 입금자명(authCode, 4자리 숫자) — 이 값을 verification_code로
     *         저장해두고 confirm 때 사용자 입력과 그대로 대조한다.
     */
    public String requestAccountTransferAuth(String bankCode, String accountNumber) {
        String organization = "0" + bankCode;

        Map<String, Object> body = Map.of(
                "organization", organization,
                "account", accountNumber,
                "inPrintType", "0"
        );

        Map<String, Object> response = callCodef("/v1/kr/bank/a/account/transfer-authentication", body);
        String authCode = extractDataField(response, "authCode");

        if (authCode == null || authCode.isBlank()) {
            log.warn("CODEF 1원인증 authCode가 비어있음 - bankCode: {}", bankCode);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "1원 인증 요청에 실패했어요. 다시 시도해주세요");
        }
        if (authCode.length() != DEPOSITOR_NAME_LENGTH) {
            log.warn("CODEF가 반환한 입금자명이 {}자가 아님({}자): {}",
                    DEPOSITOR_NAME_LENGTH, authCode.length(), authCode);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "1원 인증 요청에 실패했어요. 다시 시도해주세요");
        }

        // 테스트 편의용 — 데모 환경엔 실제 입금 알림이 없어서 DB 조회 없이 바로 확인할 수 있게 로그로 남김
        log.info("[TEST] 1원인증 CODEF 랜덤 입금자명 = {}", authCode);
        return authCode;
    }

    // ------------------------------------------------------------------
    // 내부 구현
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> callCodef(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(getAccessToken());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        String rawResponse;
        try {
            rawResponse = restTemplate.postForObject(apiBaseUrl + path, entity, String.class);
        } catch (Exception e) {
            log.error("CODEF API 호출 실패 - path: {}", path, e);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "은행 인증 서비스와 통신에 실패했어요");
        }

        Map<String, Object> response = decodeJson(rawResponse);
        if (response == null) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "은행 인증 서비스 응답이 비어있어요");
        }

        Map<String, Object> result = (Map<String, Object>) response.get("result");
        String code = result != null ? (String) result.get("code") : null;
        if (!"CF-00000".equals(code)) {
            String message = result != null ? (String) result.get("message") : "알 수 없는 오류";
            log.warn("CODEF 오류 응답 - code: {}, message: {}", code, message);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "계좌 확인에 실패했어요: " + message);
        }
        return response;
    }

    /**
     * CODEF 응답은 일반 JSON이 아니라 URL 인코딩된 JSON 문자열로 내려온다
     * (CODEF 공식 AI 예제코드에서 확인 — apiResponse를 URLDecoder.decode 후에야
     * 파싱 가능한 형태가 됨). RestTemplate의 자동 Jackson 역직렬화를 쓰면 이
     * 디코딩 단계가 빠져서 한글 필드 값이 깨지거나 파싱이 실패할 수 있어,
     * 항상 String으로 먼저 받은 뒤 디코딩 -> 파싱 순서로 처리한다.
     */
    private Map<String, Object> decodeJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            return objectMapper.readValue(decoded, Map.class);
        } catch (Exception e) {
            log.error("CODEF 응답 파싱 실패 - raw: {}", raw, e);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "은행 인증 서비스 응답을 처리하지 못했어요");
        }
    }

    @SuppressWarnings("unchecked")
    private String extractDataField(Map<String, Object> response, String field) {
        Object data = response.get("data");
        if (data instanceof Map) {
            Object value = ((Map<String, Object>) data).get(field);
            return value != null ? value.toString() : null;
        }
        if (data instanceof List && !((List<?>) data).isEmpty()) {
            Object first = ((List<?>) data).get(0);
            if (first instanceof Map) {
                Object value = ((Map<String, Object>) first).get(field);
                return value != null ? value.toString() : null;
            }
        }
        return null;
    }

    private String getAccessToken() {
        String cached = redisTemplate.opsForValue().get(TOKEN_REDIS_KEY);
        if (cached != null) {
            return cached;
        }
        String token = issueAccessToken();
        redisTemplate.opsForValue().set(TOKEN_REDIS_KEY, token, TOKEN_TTL_DAYS, TimeUnit.DAYS);
        return token;
    }

    // access_token 필드명은 CODEF AI 예제코드(extractToken)로 확인 완료.
    private String issueAccessToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String auth = clientId + ":" + clientSecret;
        headers.set("Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "read");

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);

        String rawResponse;
        try {
            rawResponse = restTemplate.postForObject(oauthUrl, entity, String.class);
        } catch (Exception e) {
            log.error("CODEF accessToken 발급 실패", e);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "은행 인증 서비스 연결에 실패했어요");
        }

        // CODEF AI 예제코드는 토큰 응답은 디코딩 없이 바로 파싱하지만(extractToken),
        // 공식 가이드 예제는 토큰 응답도 URL 디코딩을 거친다 — 두 예제가 서로 다르다.
        // 일반 텍스트에 URL 디코딩을 걸어도 안전(무해)하므로 안전하게 항상 디코딩한다.
        Map<String, Object> response = decodeJson(rawResponse);
        if (response == null || response.get("access_token") == null) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "은행 인증 토큰 발급에 실패했어요");
        }
        return (String) response.get("access_token");
    }
}
