package com.aewol.external.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

class SolapiSmsSenderTest {

    private static final Instant NOW = Instant.parse("2026-08-14T01:02:03Z");
    private static final String SALT = "fixed-test-salt";

    @Test
    void sendsOfficialRequestWithHmacAuthorizationAndMessageBody() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        SolapiSmsSender sender = sender(restTemplate);
        String signature = hmac("dummy-secret", NOW.toString() + SALT);

        server.expect(request -> {
                    assertEquals(SolapiSmsSender.SEND_URL, request.getURI().toString());
                    assertEquals(HttpMethod.POST, request.getMethod());
                    assertEquals("HMAC-SHA256 apiKey=dummy-key, date=" + NOW + ", salt=" + SALT
                                    + ", signature=" + signature,
                            request.getHeaders().getFirst("Authorization"));
                    JsonNode message = new ObjectMapper()
                            .readTree(((MockClientHttpRequest) request).getBodyAsString())
                            .get("messages").get(0);
                    assertEquals("00000000", message.get("from").asText());
                    assertEquals("01000000000", message.get("to").asText());
                    assertEquals("test message", message.get("text").asText());
                })
                .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));

        sender.send("01000000000", "test message");
        server.verify();
    }

    @Test
    void mapsProviderHttpErrorWithoutExposingRawResponse() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        SolapiSmsSender sender = sender(restTemplate);
        server.expect(request -> assertEquals(SolapiSmsSender.SEND_URL, request.getURI().toString()))
                .andRespond(withBadRequest().body("{\"errorMessage\":\"sensitive provider detail\"}"));

        SmsSendException exception = assertThrows(SmsSendException.class,
                () -> sender.send("01000000000", "test message"));

        assertEquals("SOLAPI request failed", exception.getMessage());
        assertEquals(SmsFailureReason.TRANSPORT_OR_HTTP, exception.getReason());
        server.verify();
    }

    @Test
    void rejectsHttp200WhenSolapiReportsMessageRegistrationFailure() {
        assertRegistrationFailure("{\"failedMessageList\":[{\"to\":\"must-not-be-logged\"}],"
                + "\"groupInfo\":{\"count\":{\"registeredSuccess\":0,\"registeredFailed\":1}}}");
    }

    @Test
    void rejectsIncompleteOrUnexpectedHttp200Responses() {
        assertRegistrationFailure(null);
        assertRegistrationFailure("{\"failedMessageList\":[],\"groupInfo\":null}");
        assertRegistrationFailure("{\"failedMessageList\":[],\"groupInfo\":{\"count\":null}}");
        assertRegistrationFailure("{\"failedMessageList\":[],\"groupInfo\":{\"count\":"
                + "{\"registeredSuccess\":0,\"registeredFailed\":0}}}");
        assertRegistrationFailure("{\"failedMessageList\":[],\"groupInfo\":{\"count\":"
                + "{\"registeredSuccess\":1,\"registeredFailed\":1}}}");
        assertRegistrationFailure("{\"failedMessageList\":[{}],\"groupInfo\":{\"count\":"
                + "{\"registeredSuccess\":1,\"registeredFailed\":0}}}");
    }

    @Test
    void mapsServerAndTransportErrorsToSmsSendException() {
        RestTemplate serverErrorTemplate = new RestTemplate();
        MockRestServiceServer serverError = MockRestServiceServer.bindTo(serverErrorTemplate).build();
        serverError.expect(request -> { })
                .andRespond(withServerError());
        assertEquals("SOLAPI request failed", assertThrows(SmsSendException.class,
                () -> sender(serverErrorTemplate).send("01000000000", "test message")).getMessage());
        serverError.verify();

        RestTemplate transportErrorTemplate = new RestTemplate();
        MockRestServiceServer transportError = MockRestServiceServer.bindTo(transportErrorTemplate).build();
        transportError.expect(request -> { })
                .andRespond(request -> {
                    throw new ResourceAccessException("simulated timeout");
                });
        SmsSendException transport = assertThrows(SmsSendException.class,
                () -> sender(transportErrorTemplate).send("01000000000", "test message"));
        assertEquals("SOLAPI request failed", transport.getMessage());
        assertEquals(SmsFailureReason.TRANSPORT_OR_HTTP, transport.getReason());
        transportError.verify();
    }

    @Test
    void mapsUnauthorizedToAuthReasonWithoutExposingRawResponse() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> { })
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"errorCode\":\"InvalidAPIKey\"}"));

        SmsSendException exception = assertThrows(SmsSendException.class,
                () -> sender(restTemplate).send("01000000000", "test message"));

        assertEquals("SOLAPI request failed", exception.getMessage());
        assertEquals(SmsFailureReason.AUTH, exception.getReason());
        server.verify();
    }

    @Test
    void mapsSenderRegistrationFailureWithoutLoggingPhoneNumber() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> { })
                .andRespond(withSuccess(
                        "{\"failedMessageList\":[{\"to\":\"must-not-be-logged\",\"statusCode\":\"1024\","
                                + "\"statusMessage\":\"발신번호 미등록\"}],"
                                + "\"groupInfo\":{\"count\":{\"registeredSuccess\":0,\"registeredFailed\":1}}}",
                        MediaType.APPLICATION_JSON));

        SmsSendException exception = assertThrows(SmsSendException.class,
                () -> sender(restTemplate).send("01000000000", "test message"));

        assertEquals("SOLAPI message registration failed", exception.getMessage());
        assertEquals(SmsFailureReason.SENDER_NOT_APPROVED, exception.getReason());
        server.verify();
    }

    @Test
    void missingCredentialsFailBeforeNetworkCall() {
        SolapiSmsSender sender = new SolapiSmsSender(new RestTemplate(), "", "", "",
                Clock.fixed(NOW, ZoneOffset.UTC), () -> SALT);
        SmsSendException exception = assertThrows(SmsSendException.class,
                () -> sender.send("01000000000", "test message"));
        assertEquals("SOLAPI is not configured", exception.getMessage());
        assertEquals(SmsFailureReason.NOT_CONFIGURED, exception.getReason());
    }

    private SolapiSmsSender sender(RestTemplate restTemplate) {
        return new SolapiSmsSender(restTemplate, "dummy-key", "dummy-secret", "00000000",
                Clock.fixed(NOW, ZoneOffset.UTC), () -> SALT);
    }

    private void assertRegistrationFailure(String responseBody) {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        if (responseBody == null) {
            server.expect(request -> { }).andRespond(withSuccess());
        } else {
            server.expect(request -> { })
                    .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
        }

        SmsSendException exception = assertThrows(SmsSendException.class,
                () -> sender(restTemplate).send("01000000000", "test message"));
        assertEquals("SOLAPI message registration failed", exception.getMessage());
        assertEquals(SmsFailureReason.PROVIDER_REJECTED, exception.getReason());
        server.verify();
    }

    private String successResponse() {
        return "{\"failedMessageList\":[],\"messageList\":[{\"messageId\":\"dummy\"}],"
                + "\"groupInfo\":{\"groupId\":\"dummy\",\"count\":"
                + "{\"total\":1,\"sentSuccess\":0,\"registeredSuccess\":1,"
                + "\"registeredFailed\":0}}}";
    }

    private String hmac(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
