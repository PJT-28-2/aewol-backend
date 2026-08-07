package com.aewol.external.codef;

import com.aewol.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
public class CodefClient {

    private final RestTemplate codefRestTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // develop 병합으로 공용 restTemplate()에 @Primary가 붙으면서, 필드명을 빈 이름과
    // 맞추는 방식(name-matching)만으로는 더 이상 codefRestTemplate이 주입되지 않는다
    // (Spring은 후보가 여러 개일 때 이름 매칭보다 @Primary를 우선한다). GeminiVisionClient가
    // 이미 쓰고 있는 것과 같은 방식으로 @Qualifier를 명시한다.
    public CodefClient(@Qualifier("codefRestTemplate") RestTemplate codefRestTemplate,
                        RedisTemplate<String, String> redisTemplate) {
        this.codefRestTemplate = codefRestTemplate;
        this.redisTemplate = redisTemplate;
    }

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

    // 1원 이체 요청은 실제 돈이 오가는 멱등하지 않은 호출이라, 같은 계좌로 짧은 시간
    // 안에 중복 요청(더블클릭, 프론트 재시도, 네트워크 타임아웃 후 재시도 등)이 들어오면
    // CODEF가 매번 새 입금자명으로 별도 이체를 발생시킬 수 있다(CodeRabbit 지적,
    // 2026-08-07). bankCode+accountNumber 기준으로 짧은 락을 걸어서 막는다 — 잠금
    // 해제는 따로 하지 않고 TTL 만료에만 맡긴다(성공/실패 여부와 무관하게 쿨다운으로
    // 동작해야 진짜 중복 클릭을 막을 수 있음).
    private static final String TRANSFER_AUTH_LOCK_PREFIX = "codef:transfer-auth-lock:";
    private static final long TRANSFER_AUTH_LOCK_TTL_SECONDS = 10;

    // CODEF inPrintType="1"(랜덤 한글 단어)은 CODEF가 자체 사전에서 단어를 골라주는
    // 방식이라 길이를 우리가 통제할 수 없다 — 4자, 5자, 6자가 다 나온다(2026-08-06 확인).
    // 그래서 inPrintType="9"(고객사 직접 입력)로 바꿔서, 우리가 직접 4자 한글 단어 풀에서
    // 랜덤으로 골라 inPrintContent에 실어 보낸다. CODEF는 이 문자열 그대로 실제 1원
    // 이체의 입금자명으로 사용하고, 응답 authCode도 같은 값을 그대로 돌려준다(CODEF
    // 개발가이드 "기타 계좌 인증(1원 이체) API" 문서 예시 참고). 실제 돈이 오가는 진짜
    // 1원 인증이라는 성격은 그대로 유지되면서 길이는 항상 4자로 고정된다(2026-08-06).
    //
    // 후보 10개 + java.util.Random은 brute-force 범위가 너무 좁다(CodeRabbit 지적,
    // 2026-08-07). 2음절 수식어 x 2음절 명사 조합으로 뽑아서 4자 고정은 유지하면서
    // 후보 수를 늘린다(18 x 21 = 378가지). 예측 가능한 PRNG도 실제 인증 값 생성에
    // 쓰기엔 부적절해서 SecureRandom으로 교체한다.
    private static final List<String> DEPOSITOR_NAME_MODIFIERS = List.of(
            "노란", "파란", "하얀", "푸른", "포근", "달콤", "따뜻", "몽글",
            "폭신", "상큼", "촉촉", "살랑", "반짝", "몽실", "산뜻", "새콤",
            "뽀송", "몰랑"
    );
    private static final List<String> DEPOSITOR_NAME_NOUNS = List.of(
            "구름", "하늘", "토끼", "바다", "사탕", "담요", "베개", "나무",
            "바람", "햇살", "별빛", "노을", "이슬", "파도", "나비", "참새",
            "우산", "모래", "눈꽃", "단풍", "새싹"
    );
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private static String randomDepositorName() {
        String modifier = DEPOSITOR_NAME_MODIFIERS.get(RANDOM.nextInt(DEPOSITOR_NAME_MODIFIERS.size()));
        String noun = DEPOSITOR_NAME_NOUNS.get(RANDOM.nextInt(DEPOSITOR_NAME_NOUNS.size()));
        return modifier + noun;
    }

    /**
     * CODEF 계좌 인증(1원 이체) 요청.
     * bankCode는 bank_master의 금융결제원 3자리 코드 — CODEF organization(4자리)으로
     * 패딩해서 보낸다("0" + bankCode, 예: "004" -> "0004").
     * inPrintType=9(고객사 직접 입력)로 수식어+명사 조합(둘 다 2음절)을 골라
     * inPrintContent로 보낸다 — 우리가 고른 값이 그대로 실제 1원 이체의 입금자명이
     * 되므로 길이가 항상 4자로 고정된다. 프론트(AccountAuthOneWon.vue)는 응답의
     * depositorNameLength(항상 4)만큼 입력 칸을 그린다.
     *
     * TODO: 데모 서버는 실제 인증코드가 아니라 랜덤 성공/실패 테스트 데이터를
     * 반환한다(CODEF 문서 명시) — 정식 버전 전환 전까지는 이 검증이 항상
     * 신뢰할 수 있는 건 아니라는 점 감안 필요.
     *
     * 같은 bankCode+accountNumber로 {@value #TRANSFER_AUTH_LOCK_TTL_SECONDS}초 안에
     * 재요청이 들어오면 CONFLICT로 막는다 — 실제 돈이 오가는 호출이라 중복 이체를
     * 방지하기 위함(2026-08-07).
     *
     * @return 우리가 고른 입금자명(authCode) — 이 값을 verification_code로 저장해두고
     *         confirm 때 사용자 입력과 그대로 대조한다.
     */
    public String requestAccountTransferAuth(String bankCode, String accountNumber) {
        String lockKey = TRANSFER_AUTH_LOCK_PREFIX + bankCode + ":" + accountNumber;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", TRANSFER_AUTH_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 처리 중인 1원 인증 요청이 있어요. 잠시 후 다시 시도해주세요");
        }

        String organization = "0" + bankCode;
        String depositorName = randomDepositorName();

        Map<String, Object> body = Map.of(
                "organization", organization,
                "account", accountNumber,
                "inPrintType", "9",
                "inPrintContent", depositorName
        );

        Map<String, Object> response = callCodef("/v1/kr/bank/a/account/transfer-authentication", body);
        String authCode = extractDataField(response, "authCode");

        if (authCode == null || authCode.isBlank()) {
            log.warn("CODEF 1원인증 authCode가 비어있음 - bankCode: {}", bankCode);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "1원 인증 요청에 실패했어요. 다시 시도해주세요");
        }

        // 테스트 편의용 — 데모 환경엔 실제 입금 알림이 없어서 DB 조회 없이 바로 확인할 수 있게 로그로 남김
        log.info("[TEST] 1원인증 입금자명 = {}", authCode);
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
            rawResponse = codefRestTemplate.postForObject(apiBaseUrl + path, entity, String.class);
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

    /**
     * 토큰 응답은 계좌/이체 API 응답과 달리 일반 JSON일 수 있어서, URL 디코딩을 먼저
     * 걸면 access_token 안의 '+'가 공백으로 바뀌거나 잘못된 '%' 시퀀스에서 디코딩
     * 자체가 실패할 수 있다(CodeRabbit 지적, 2026-08-06). 그래서 순수 JSON 파싱을
     * 먼저 시도하고, access_token을 못 찾았을 때만 URL 디코딩을 재시도한다.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseTokenResponse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(raw, Map.class);
            if (parsed.get("access_token") != null) {
                return parsed;
            }
        } catch (Exception e) {
            // 순수 JSON으로 못 읽으면 아래에서 URL 디코딩 후 재시도
        }
        try {
            String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            return objectMapper.readValue(decoded, Map.class);
        } catch (Exception e) {
            log.error("CODEF 토큰 응답 파싱 실패 - raw: {}", raw, e);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "은행 인증 토큰 응답을 처리하지 못했어요");
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
            rawResponse = codefRestTemplate.postForObject(oauthUrl, entity, String.class);
        } catch (Exception e) {
            log.error("CODEF accessToken 발급 실패", e);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "은행 인증 서비스 연결에 실패했어요");
        }

        // CODEF AI 예제코드는 토큰 응답은 디코딩 없이 바로 파싱하지만(extractToken),
        // 공식 가이드 예제는 토큰 응답도 URL 디코딩을 거친다 — 두 예제가 서로 다르다.
        // parseTokenResponse가 순수 JSON을 먼저 시도하고 실패할 때만 디코딩을 재시도한다.
        Map<String, Object> response = parseTokenResponse(rawResponse);
        if (response == null || response.get("access_token") == null) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "은행 인증 토큰 발급에 실패했어요");
        }
        return (String) response.get("access_token");
    }
}
