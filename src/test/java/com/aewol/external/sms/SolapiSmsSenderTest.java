package com.aewol.external.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

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
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

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
        server.verify();
    }

    @Test
    void missingCredentialsFailBeforeNetworkCall() {
        SolapiSmsSender sender = new SolapiSmsSender(new RestTemplate(), "", "", "",
                Clock.fixed(NOW, ZoneOffset.UTC), () -> SALT);
        assertEquals("SOLAPI is not configured",
                assertThrows(SmsSendException.class,
                        () -> sender.send("01000000000", "test message")).getMessage());
    }

    private SolapiSmsSender sender(RestTemplate restTemplate) {
        return new SolapiSmsSender(restTemplate, "dummy-key", "dummy-secret", "00000000",
                Clock.fixed(NOW, ZoneOffset.UTC), () -> SALT);
    }

    private String hmac(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
