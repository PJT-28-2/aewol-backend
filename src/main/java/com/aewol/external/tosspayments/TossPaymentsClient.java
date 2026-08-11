package com.aewol.external.tosspayments;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * TossPayments 결제 승인/취소 클라이언트.
 *
 * 분류 규칙의 근거는 .omc/research/toss-api-spec.md 및
 * .omc/plans/qr-payment-toss-demo-plan.md의 "외부 연동" AC다. 핵심 불변식: HTTP
 * 상태코드가 아니라 응답 본문의 {@code code} 문자열로 분류한다 — 상태코드 기반
 * 분류는 INVALID_API_KEY(400, 설정 오류)와 REJECT_CARD_COMPANY(403, 카드 거절)를
 * 정반대로 오분류한다.
 */
@Slf4j
@Component
public class TossPaymentsClient {

    private static final String CONFIRM_URL = "https://api.tosspayments.com/v1/payments/confirm";
    private static final String CANCEL_URL = "https://api.tosspayments.com/v1/payments/{paymentKey}/cancel";

    private static final String STATUS_DONE = "DONE";

    // 이미 승인됨 — Toss가 승인을 보유하고 있음을 의미하므로 클레임을 유지해야 한다.
    private static final Set<String> ALREADY_APPROVED_CODES = Set.of(
            "ALREADY_PROCESSED_PAYMENT",
            "DUPLICATED_REQUEST"
    );

    // 확정적 거부 — Toss가 요청을 받았고 승인이 나지 않았음이 확정됨. 클레임 해제 가능.
    private static final Set<String> DEFINITIVE_REJECTION_CODES = Set.of(
            "REJECT_CARD_COMPANY",
            "REJECT_CARD_PAYMENT",
            "REJECT_ACCOUNT_PAYMENT",
            "FDS_ERROR",
            "NOT_AVAILABLE_BANK",
            "INVALID_REJECT_CARD",
            "INVALID_STOPPED_CARD",
            "BELOW_MINIMUM_AMOUNT",
            "INVALID_REQUEST",
            "PAY_PROCESS_CANCELED",
            "PAY_PROCESS_ABORTED",
            "NOT_FOUND_PAYMENT_SESSION"
    );

    // 설정 오류 — Toss가 요청을 인증조차 하지 않았으므로 승인을 보유할 수 없다.
    private static final Set<String> CONFIG_ERROR_CODES = Set.of(
            "INVALID_API_KEY",
            "UNAUTHORIZED_KEY",
            "INCORRECT_BASIC_AUTH_FORMAT",
            "FORBIDDEN_REQUEST"
    );

    // 위 세 집합에 없는 모든 code는 INDETERMINATE로 떨어진다(안전한 기본값). 특히
    // PROVIDER_ERROR(400), IDEMPOTENT_REQUEST_PROCESSING(409), 모든 5xx가 여기 포함된다.
    //
    // 함정: FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING(500)의 메시지는 "결제가 완료되지
    // 않았어요"라 확정적 거부처럼 읽히지만, 5xx라 Toss 내부에서 실제로 어느 단계까지
    // 진행됐는지 알 수 없다. 메시지를 믿고 DEFINITIVE_REJECTION_CODES에 넣으면 클레임이
    // 풀려서 재시도가 가능해지고, 실제로는 승인이 이미 나 있었을 경우 이중결제로 이어진다.
    // 그래서 이 코드는 위 세 집합 어디에도 없다 — 의도적으로 열거하지 않았다.

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 서버 confirm/cancel 호출에는 secretKey만 쓴다. clientKey는 프론트 SDK 초기화용이라
    // 이 클라이언트에서는 사용하지 않지만, external.toss.* 설정 컨벤션(KakaoAuthClient와
    // 동일하게 두 값을 함께 주입)을 맞추고, 향후 clientKey를 프론트에 내려주는 엔드포인트가
    // 필요해지면 바로 재사용할 수 있도록 여기 둔다.
    @Value("${external.toss.client-key:}")
    private String clientKey;

    @Value("${external.toss.secret-key:}")
    private String secretKey;

    // restTemplate()이 @Primary(RestTemplateConfig.java:16-18)라서 필드명 매칭만으로는
    // tossRestTemplate이 주입되지 않는다 — GeminiVisionClient.java:43과 동일한 함정.
    public TossPaymentsClient(@Qualifier("tossRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 테스트/운영 base URL이 동일(https://api.tosspayments.com)하고 키로만 구분되므로,
     * 시크릿 키를 잘못 넣으면(live_ 키) 데모 중 실제 결제가 발생한다. 부팅 시 test_
     * 접두사인지 검사해 사고를 눈에 띄게 만든다. 미설정(로컬 개발)은 검사를 건너뛴다.
     * 하드 실패 대신 ERROR 로그로 남긴다 — 이 값이 잘못됐다고 앱 전체 기동을 막으면
     * 다른 기능(결제 무관)까지 함께 막혀서 데모 자체가 불가능해질 수 있기 때문이다.
     */
    @PostConstruct
    void checkSecretKeyIsTestMode() {
        if (secretKey == null || secretKey.isEmpty()) {
            return;
        }
        if (!secretKey.startsWith("test_")) {
            log.error("[TOSS_LIVE_KEY_DETECTED] external.toss.secret-key가 'test_'로 시작하지 않습니다. "
                    + "TossPayments는 테스트/운영 API가 같은 base URL을 쓰고 키로만 구분되므로, "
                    + "이 상태로 결제를 진행하면 실제 돈이 빠져나갈 수 있습니다. 즉시 확인하세요.");
        }
    }

    /**
     * TossPayments 결제 승인. 반환된 {@link TossConfirmResult#getOutcome()}으로
     * 다섯 가지 결과를 분기한다. HTTP 200이라도 status가 "DONE"이 아니면(가상계좌
     * WAITING_FOR_DEPOSIT 등) 승인으로 보지 않는다.
     */
    public TossConfirmResult confirmPayment(String paymentKey, String orderId, long amount) {
        log.info("TossPayments 결제 승인 요청 - paymentKey: {}, orderId: {}, amount: {}", paymentKey, orderId, amount);

        Map<String, Object> requestBody = Map.of(
                "paymentKey", paymentKey,
                "orderId", orderId,
                "amount", amount
        );
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, authHeaders());

        String rawResponse;
        try {
            rawResponse = restTemplate.postForObject(CONFIRM_URL, entity, String.class);
        } catch (HttpStatusCodeException e) {
            // Toss가 4xx/5xx로 응답 — 에러 본문({code, message})은 예외 안에 실려 있다
            // (RestTemplate 기본 에러 핸들러가 예외로 변환하므로 이 경로가 아니면 볼 수 없음).
            return classifyErrorBody(e.getResponseBodyAsString(), orderId);
        } catch (ResourceAccessException e) {
            // 소켓 타임아웃/커넥션 실패 — 카드사 승인이 실제로 났는지 알 수 없다.
            log.error("TossPayments confirm 호출 실패(네트워크) - orderId: {}", orderId, e);
            return TossConfirmResult.of(TossConfirmOutcome.INDETERMINATE, null, e.getMessage(), null);
        } catch (RestClientException e) {
            // 안전망: 위에서 다루지 않은 모든 RestTemplate 예외도 불확정으로 처리한다.
            log.error("TossPayments confirm 호출 실패(알 수 없음) - orderId: {}", orderId, e);
            return TossConfirmResult.of(TossConfirmOutcome.INDETERMINATE, null, e.getMessage(), null);
        }

        Map<String, Object> body = parseJson(rawResponse);
        if (body == null) {
            log.error("TossPayments confirm 응답 파싱 실패 - orderId: {}, raw: {}", orderId, rawResponse);
            return TossConfirmResult.of(TossConfirmOutcome.INDETERMINATE, null, null, null);
        }

        String status = (String) body.get("status");
        if (STATUS_DONE.equals(status)) {
            Number totalAmount = (Number) body.get("totalAmount");
            if (totalAmount == null) {
                log.error("TossPayments confirm 응답에 totalAmount가 없음 - orderId: {}, raw: {}", orderId, rawResponse);
                return TossConfirmResult.of(TossConfirmOutcome.INDETERMINATE, null, "missing totalAmount", body);
            }
            return TossConfirmResult.success(totalAmount.longValue(), body);
        }

        // HTTP 200이지만 status != DONE(예: 가상계좌 WAITING_FOR_DEPOSIT) — Toss가 결제
        // 레코드를 이미 보유하고 있으므로 확정적 거부가 아니다. 안전한 기본값(불확정)으로
        // 처리해 클레임을 유지하고 수동 확인을 유도한다.
        log.warn("TossPayments confirm 200이지만 status != DONE - orderId: {}, status: {}", orderId, status);
        return TossConfirmResult.of(TossConfirmOutcome.INDETERMINATE, null, "status=" + status, body);
    }

    private TossConfirmResult classifyErrorBody(String rawBody, String orderId) {
        Map<String, Object> body = parseJson(rawBody);
        if (body == null) {
            // 본문 파싱 실패 — code를 알 수 없다. INVALID_API_KEY(400)/REJECT_CARD_COMPANY(403)
            // 사례가 보여주듯 HTTP 상태만으로는 설정 오류와 카드 거절을 구분할 수 없으므로,
            // 본문이 없을 때 상태코드로 되돌아가 추정하면 이 클라이언트가 막으려는 바로 그
            // 오분류를 재현하게 된다. 상태코드와 무관하게 항상 불확정으로 처리한다.
            log.error("TossPayments confirm 에러 응답 파싱 실패 - orderId: {}, raw: {}", orderId, rawBody);
            return TossConfirmResult.of(TossConfirmOutcome.INDETERMINATE, null, null, null);
        }

        String code = (String) body.get("code");
        String message = (String) body.get("message");
        log.warn("TossPayments confirm 에러 응답 - orderId: {}, code: {}, message: {}", orderId, code, message);

        if (code == null) {
            return TossConfirmResult.of(TossConfirmOutcome.INDETERMINATE, null, message, body);
        }
        if (ALREADY_APPROVED_CODES.contains(code)) {
            return TossConfirmResult.of(TossConfirmOutcome.ALREADY_APPROVED, code, message, body);
        }
        if (DEFINITIVE_REJECTION_CODES.contains(code)) {
            return TossConfirmResult.of(TossConfirmOutcome.DEFINITIVE_REJECTION, code, message, body);
        }
        if (CONFIG_ERROR_CODES.contains(code)) {
            return TossConfirmResult.of(TossConfirmOutcome.CONFIG_ERROR, code, message, body);
        }
        // 미열거 code 전부(PROVIDER_ERROR, IDEMPOTENT_REQUEST_PROCESSING,
        // FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING, 그 외 모든 5xx 포함) — 안전한 기본값.
        return TossConfirmResult.of(TossConfirmOutcome.INDETERMINATE, code, message, body);
    }

    /**
     * 승인 후 원장 기록 실패 시의 보상(compensation) 호출. 전액취소이므로 cancelAmount는
     * 보내지 않는다(생략 시 전액취소 — .omc/research/toss-api-spec.md §2.3). 어떤 경로로도
     * 예외를 밖으로 던지지 않는다 — 이 호출을 감싸고 있는 원래 실패 처리 로직의 흐름을
     * 가리지 않기 위해 항상 {@link TossCancelResult}로 결과를 반환한다.
     */
    public TossCancelResult cancelPayment(String paymentKey, String cancelReason) {
        log.info("TossPayments 결제 취소(보상) 요청 - paymentKey: {}", paymentKey);

        Map<String, Object> requestBody = Map.of("cancelReason", cancelReason);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, authHeaders());

        try {
            restTemplate.postForObject(CANCEL_URL, entity, String.class, paymentKey);
            log.info("TossPayments 결제 취소(보상) 성공 - paymentKey: {}", paymentKey);
            return TossCancelResult.success();
        } catch (HttpStatusCodeException e) {
            Map<String, Object> body = parseJson(e.getResponseBodyAsString());
            String code = body != null ? (String) body.get("code") : null;
            String message = body != null ? (String) body.get("message") : e.getStatusText();

            if ("ALREADY_CANCELED_PAYMENT".equals(code)) {
                // 이미 취소된 결제 — 목표(취소 상태)는 이미 달성되어 있으므로 보상 성공으로
                // 흡수한다(.omc/research/toss-api-spec.md §3.3 권고).
                log.info("TossPayments 취소 응답: 이미 취소된 결제 - paymentKey: {}", paymentKey);
                return TossCancelResult.alreadyCanceled(code, message);
            }
            log.error("TossPayments 결제 취소(보상) 실패 - paymentKey: {}, code: {}, message: {}",
                    paymentKey, code, message);
            return TossCancelResult.failure(code, message);
        } catch (RestClientException e) {
            log.error("TossPayments 결제 취소(보상) 호출 실패(네트워크/알 수 없음) - paymentKey: {}", paymentKey, e);
            return TossCancelResult.failure(null, e.getMessage());
        }
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, basicAuthHeader());
        return headers;
    }

    /**
     * 시크릿 키가 username, 비밀번호는 없음 — 뒤에 콜론만 붙여 인코딩한다. 콜론을
     * 빠뜨리면 Toss가 INCORRECT_BASIC_AUTH_FORMAT(403)을 반환한다(콜론 필수,
     * .omc/research/toss-api-spec.md §1.2).
     */
    private String basicAuthHeader() {
        String credentials = secretKey + ":";
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
