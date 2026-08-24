package com.aewol.external.sms;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class SolapiSmsSender implements SmsSender {

    static final String SEND_URL = "https://api.solapi.com/messages/v4/send-many/detail";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** SOLAPI 발신번호 미등록·미승인 코드. 응답 본문은 로그에 남기지 않는다. */
    private static final Set<String> SENDER_FAILURE_CODES = Set.of("1024", "1041", "1061");

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String apiSecret;
    private final String sender;
    private final Clock clock;
    private final Supplier<String> saltSupplier;

    @Autowired
    public SolapiSmsSender(
            @Qualifier("solapiRestTemplate") RestTemplate restTemplate,
            @Value("${external.solapi.api-key:}") String apiKey,
            @Value("${external.solapi.api-secret:}") String apiSecret,
            @Value("${external.solapi.sender:}") String sender) {
        this(restTemplate, apiKey, apiSecret, sender, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    SolapiSmsSender(RestTemplate restTemplate, String apiKey, String apiSecret, String sender,
                    Clock clock, Supplier<String> saltSupplier) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.sender = sender;
        this.clock = clock;
        this.saltSupplier = saltSupplier;
    }

    @Override
    public void send(String to, String text) {
        requireConfiguration();
        String date = Instant.now(clock).toString();
        String salt = saltSupplier.get();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, authorization(date, salt));
        Map<String, Object> body = Map.of("messages", List.of(Map.of(
                "from", sender,
                "to", to,
                "text", text
        )));

        try {
            ResponseEntity<SolapiSendResponse> response = restTemplate.exchange(
                    SEND_URL, HttpMethod.POST, new HttpEntity<>(body, headers), SolapiSendResponse.class);
            if (!isSingleMessageRegistered(response.getBody())) {
                SmsFailureReason reason = classifyRegistrationFailure(response.getBody());
                log.warn("SOLAPI SMS 발송 등록 결과를 신뢰할 수 없습니다. reason={}", reason);
                throw new SmsSendException("SOLAPI message registration failed", reason);
            }
        } catch (SmsSendException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            SmsFailureReason reason = classifyHttpFailure(e);
            log.warn("SOLAPI SMS 발송 호출에 실패했습니다. reason={} httpStatus={}",
                    reason, e.getRawStatusCode());
            throw new SmsSendException("SOLAPI request failed", reason, e);
        } catch (RestClientException e) {
            log.warn("SOLAPI SMS 발송 호출에 실패했습니다. reason={}", SmsFailureReason.TRANSPORT_OR_HTTP);
            throw new SmsSendException("SOLAPI request failed", SmsFailureReason.TRANSPORT_OR_HTTP, e);
        }
    }

    private boolean isSingleMessageRegistered(SolapiSendResponse response) {
        if (response == null || response.getGroupInfo() == null
                || response.getGroupInfo().getCount() == null
                || response.getFailedMessageList() == null) {
            return false;
        }
        SolapiSendResponse.Count count = response.getGroupInfo().getCount();
        return Integer.valueOf(1).equals(count.getRegisteredSuccess())
                && Integer.valueOf(0).equals(count.getRegisteredFailed())
                && response.getFailedMessageList().isEmpty();
    }

    String authorization(String date, String salt) {
        return "HMAC-SHA256 apiKey=" + apiKey
                + ", date=" + date
                + ", salt=" + salt
                + ", signature=" + signature(date, salt);
    }

    private String signature(String date, String salt) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal((date + salt).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("SOLAPI authorization could not be generated", e);
        }
    }

    private void requireConfiguration() {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(apiSecret) || !StringUtils.hasText(sender)) {
            log.error("SOLAPI SMS 발송 전에 설정이 비어 있습니다. reason={}", SmsFailureReason.NOT_CONFIGURED);
            throw new SmsSendException("SOLAPI is not configured", SmsFailureReason.NOT_CONFIGURED);
        }
    }

    SmsFailureReason classifyHttpFailure(HttpStatusCodeException exception) {
        int status = exception.getRawStatusCode();
        if (status == 401 || status == 403) {
            return SmsFailureReason.AUTH;
        }
        if (status == 400 && isSenderFailureMessage(exception.getResponseBodyAsString())) {
            return SmsFailureReason.SENDER_NOT_APPROVED;
        }
        return SmsFailureReason.TRANSPORT_OR_HTTP;
    }

    SmsFailureReason classifyRegistrationFailure(SolapiSendResponse response) {
        if (response == null || response.getFailedMessageList() == null) {
            return SmsFailureReason.PROVIDER_REJECTED;
        }
        for (SolapiSendResponse.FailedMessage failed : response.getFailedMessageList()) {
            if (isSenderFailure(failed)) {
                return SmsFailureReason.SENDER_NOT_APPROVED;
            }
        }
        return SmsFailureReason.PROVIDER_REJECTED;
    }

    private boolean isSenderFailure(SolapiSendResponse.FailedMessage failed) {
        if (failed == null) {
            return false;
        }
        String code = failed.getStatusCode();
        if (code != null && SENDER_FAILURE_CODES.contains(code)) {
            return true;
        }
        return isSenderFailureMessage(failed.getStatusMessage());
    }

    private boolean isSenderFailureMessage(String message) {
        return message != null && message.contains("발신번호");
    }
}
