package com.aewol.external.tosspayments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * confirmPayment/cancelPayment의 분류 로직을 검증한다. 이 클라이언트의 핵심 불변식은
 * "분류는 HTTP 상태코드가 아니라 응답 본문의 code 문자열로 한다"는 것이고, 그 이유는
 * Toss의 실제 에러 코드 체계가 상태코드 기반 분류를 양방향으로 깨기 때문이다
 * (.omc/research/toss-api-spec.md §3.1):
 *   - INVALID_API_KEY는 400인데 설정 오류 → 상태코드 기반이면 "카드 거절"로 오분류
 *   - REJECT_CARD_COMPANY는 403인데 카드 거절 → 상태코드 기반이면 "설정 오류"로 오분류
 * should_classifyAsConfigError_when* / should_classifyAsDefinitiveRejection_when* 두 테스트가
 * 바로 이 회귀를 막는 가드다.
 */
class TossPaymentsClientTest {

    private static final String CONFIRM_URL = "https://api.tosspayments.com/v1/payments/confirm";
    private static final String CANCEL_URL = "https://api.tosspayments.com/v1/payments/{paymentKey}/cancel";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RestTemplate restTemplate;
    private TossPaymentsClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new TossPaymentsClient(restTemplate);
        ReflectionTestUtils.setField(client, "secretKey", "test_sk_dummy");
    }

    private String toJson(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private HttpStatusCodeException httpError(HttpStatus status, String code, String message) throws Exception {
        byte[] body = toJson(Map.of("code", code, "message", message)).getBytes(StandardCharsets.UTF_8);
        if (status.is5xxServerError()) {
            return new HttpServerErrorException(status, status.getReasonPhrase(), body, StandardCharsets.UTF_8);
        }
        return new HttpClientErrorException(status, status.getReasonPhrase(), body, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // confirmPayment — 5분류
    // ------------------------------------------------------------------

    @Test
    @DisplayName("HTTP 200 이고 status가 DONE이면 SUCCESS이고 totalAmount를 그대로 반환한다")
    void should_returnSuccess_when_httpOkAndStatusDone() throws Exception {
        String responseJson = toJson(Map.of(
                "status", "DONE",
                "totalAmount", 15000,
                "paymentKey", "pk_test_1",
                "orderId", "order-1"
        ));
        when(restTemplate.postForObject(eq(CONFIRM_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn(responseJson);

        TossConfirmResult result = client.confirmPayment("pk_test_1", "order-1", 15000L);

        assertEquals(TossConfirmOutcome.SUCCESS, result.getOutcome());
        assertEquals(15000L, result.getTotalAmount());
    }

    @Test
    @DisplayName("HTTP 200 이지만 status가 DONE이 아니면(가상계좌 WAITING_FOR_DEPOSIT 등) INDETERMINATE로 처리한다")
    void should_returnIndeterminate_when_httpOkButStatusNotDone() throws Exception {
        String responseJson = toJson(Map.of(
                "status", "WAITING_FOR_DEPOSIT",
                "totalAmount", 15000
        ));
        when(restTemplate.postForObject(eq(CONFIRM_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn(responseJson);

        TossConfirmResult result = client.confirmPayment("pk_test_1", "order-1", 15000L);

        assertEquals(TossConfirmOutcome.INDETERMINATE, result.getOutcome());
    }

    @Test
    @DisplayName("ALREADY_PROCESSED_PAYMENT(400)는 ALREADY_APPROVED로 분류하고 클레임을 유지해야 함을 나타낸다")
    void should_classifyAsAlreadyApproved_when_alreadyProcessedPayment() throws Exception {
        when(restTemplate.postForObject(eq(CONFIRM_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(httpError(HttpStatus.BAD_REQUEST, "ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다"));

        TossConfirmResult result = client.confirmPayment("pk_test_1", "order-1", 15000L);

        assertEquals(TossConfirmOutcome.ALREADY_APPROVED, result.getOutcome());
        assertEquals("ALREADY_PROCESSED_PAYMENT", result.getCode());
    }

    @Test
    @DisplayName("DUPLICATED_REQUEST(400)도 ALREADY_APPROVED로 분류한다")
    void should_classifyAsAlreadyApproved_when_duplicatedRequest() throws Exception {
        when(restTemplate.postForObject(eq(CONFIRM_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(httpError(HttpStatus.BAD_REQUEST, "DUPLICATED_REQUEST", "중복된 요청입니다"));

        TossConfirmResult result = client.confirmPayment("pk_test_1", "order-1", 15000L);

        assertEquals(TossConfirmOutcome.ALREADY_APPROVED, result.getOutcome());
    }

    @Test
    @DisplayName("[회귀 가드] REJECT_CARD_COMPANY는 HTTP 403이지만 DEFINITIVE_REJECTION으로 분류해야 한다(CONFIG_ERROR 아님)")
    void should_classifyAsDefinitiveRejection_when_rejectCardCompanyEvenThoughStatusIs403() throws Exception {
        when(restTemplate.postForObject(eq(CONFIRM_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(httpError(HttpStatus.FORBIDDEN, "REJECT_CARD_COMPANY", "결제 승인이 거절되었습니다"));

        TossConfirmResult result = client.confirmPayment("pk_test_1", "order-1", 15000L);

        assertEquals(TossConfirmOutcome.DEFINITIVE_REJECTION, result.getOutcome());
        assertEquals("REJECT_CARD_COMPANY", result.getCode());
    }

    @Test
    @DisplayName("[회귀 가드] INVALID_API_KEY는 HTTP 400이지만 CONFIG_ERROR로 분류해야 한다(DEFINITIVE_REJECTION 아님)")
    void should_classifyAsConfigError_when_invalidApiKeyEvenThoughStatusIs400() throws Exception {
        when(restTemplate.postForObject(eq(CONFIRM_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(httpError(HttpStatus.BAD_REQUEST, "INVALID_API_KEY", "잘못된 시크릿키 연동 정보 입니다"));

        TossConfirmResult result = client.confirmPayment("pk_test_1", "order-1", 15000L);

        assertEquals(TossConfirmOutcome.CONFIG_ERROR, result.getOutcome());
        assertEquals("INVALID_API_KEY", result.getCode());
    }

    @Test
    @DisplayName("INCORRECT_BASIC_AUTH_FORMAT(403)은 CONFIG_ERROR로 분류한다")
    void should_classifyAsConfigError_when_incorrectBasicAuthFormat() throws Exception {
        when(restTemplate.postForObject(eq(CONFIRM_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(httpError(HttpStatus.FORBIDDEN, "INCORRECT_BASIC_AUTH_FORMAT", "잘못된 요청입니다. ':' 를 포함해 인코딩해주세요"));

        TossConfirmResult result = client.confirmPayment("pk_test_1", "order-1", 15000L);

        assertEquals(TossConfirmOutcome.CONFIG_ERROR, result.getOutcome());
    }

    @Test
    @DisplayName("[함정 가드] FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING(500)은 메시지가 '완료되지 않았다'여도 INDETERMINATE로 처리한다")
    void should_classifyAsIndeterminate_when_failedPaymentInternalSystemProcessing() throws Exception {
        when(restTemplate.postForObject(eq(CONFIRM_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(httpError(HttpStatus.INTERNAL_SERVER_ERROR,
                        "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "결제가 완료되지 않았어요. 다시 시도해주세요"));

        TossConfirmResult result = client.confirmPayment("pk_test_1", "order-1", 15000L);

        assertEquals(TossConfirmOutcome.INDETERMINATE, result.getOutcome());
    }

    @Test
    @DisplayName("PROVIDER_ERROR(400)는 '일시적 오류/재시도' 문구이므로 확정적 거부가 아니라 INDETERMINATE로 분류한다")
    void should_classifyAsIndeterminate_when_providerError() throws Exception {
        when(restTemplate.postForObject(eq(CONFIRM_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(httpError(HttpStatus.BAD_REQUEST, "PROVIDER_ERROR", "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요"));

        TossConfirmResult result = client.confirmPayment("pk_test_1", "order-1", 15000L);

        assertEquals(TossConfirmOutcome.INDETERMINATE, result.getOutcome());
    }

    @Test
    @DisplayName("미열거 code는 안전한 기본값인 INDETERMINATE로 분류한다")
    void should_classifyAsIndeterminate_when_codeNotEnumerated() throws Exception {
        when(restTemplate.postForObject(eq(CONFIRM_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(httpError(HttpStatus.BAD_REQUEST, "SOME_FUTURE_CODE_TOSS_MIGHT_ADD", "알 수 없는 코드"));

        TossConfirmResult result = client.confirmPayment("pk_test_1", "order-1", 15000L);

        assertEquals(TossConfirmOutcome.INDETERMINATE, result.getOutcome());
    }

    @Test
    @DisplayName("소켓 타임아웃/네트워크 오류(ResourceAccessException)는 INDETERMINATE로 분류한다")
    void should_classifyAsIndeterminate_when_resourceAccessException() {
        when(restTemplate.postForObject(eq(CONFIRM_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        TossConfirmResult result = client.confirmPayment("pk_test_1", "order-1", 15000L);

        assertEquals(TossConfirmOutcome.INDETERMINATE, result.getOutcome());
    }

    @Test
    @DisplayName("에러 응답 본문 파싱에 실패하면 HTTP 상태코드와 무관하게 INDETERMINATE로 분류한다")
    void should_classifyAsIndeterminate_when_errorBodyIsNotParseableJson() {
        HttpStatusCodeException notJson = new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request",
                "이건 JSON이 아님".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        when(restTemplate.postForObject(eq(CONFIRM_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(notJson);

        TossConfirmResult result = client.confirmPayment("pk_test_1", "order-1", 15000L);

        assertEquals(TossConfirmOutcome.INDETERMINATE, result.getOutcome());
    }

    // ------------------------------------------------------------------
    // cancelPayment — 보상 호출
    // ------------------------------------------------------------------

    @Test
    @DisplayName("취소 API가 정상 응답하면 성공을 보고한다")
    void should_reportSuccess_when_cancelSucceeds() {
        when(restTemplate.postForObject(eq(CANCEL_URL), any(HttpEntity.class), eq(String.class), eq("pk_test_1")))
                .thenReturn("{}");

        TossCancelResult result = client.cancelPayment("pk_test_1", "보상 취소");

        assertTrue(result.isSuccess());
        assertFalse(result.isAlreadyCanceled());
    }

    @Test
    @DisplayName("ALREADY_CANCELED_PAYMENT는 이미 목표(취소 상태)가 달성된 것이므로 성공으로 흡수한다")
    void should_absorbAsSuccess_when_alreadyCanceledPayment() throws Exception {
        when(restTemplate.postForObject(eq(CANCEL_URL), any(HttpEntity.class), eq(String.class), eq("pk_test_1")))
                .thenThrow(httpError(HttpStatus.BAD_REQUEST, "ALREADY_CANCELED_PAYMENT", "이미 취소된 결제 입니다"));

        TossCancelResult result = client.cancelPayment("pk_test_1", "보상 취소");

        assertTrue(result.isSuccess());
        assertTrue(result.isAlreadyCanceled());
    }

    @Test
    @DisplayName("취소 실패는 예외를 던지지 않고 실패 결과로 반환한다 — 원래 실패 처리 흐름을 가리지 않기 위함")
    void should_reportFailure_withoutThrowing_when_cancelFails() throws Exception {
        when(restTemplate.postForObject(eq(CANCEL_URL), any(HttpEntity.class), eq(String.class), eq("pk_test_1")))
                .thenThrow(httpError(HttpStatus.FORBIDDEN, "NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다"));

        TossCancelResult result = client.cancelPayment("pk_test_1", "보상 취소");

        assertFalse(result.isSuccess());
        assertFalse(result.isAlreadyCanceled());
        assertEquals("NOT_CANCELABLE_PAYMENT", result.getCode());
    }
}
