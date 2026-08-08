package com.aewol.external.apms;

import com.aewol.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApmsClientTest {

    private final ApmsClient client = new ApmsClient(null);

    @Test
    void should_encodeServiceKeyOnce_when_encodedServiceKeyIsConfigured() {
        ReflectionTestUtils.setField(client, "baseUrl", "https://example.test/animalInfo_v3");
        ReflectionTestUtils.setField(client, "serviceKey", "abc%2Bdef%2Fghi%3D");

        URI uri = client.buildRequestUri("123456789012", "홍길동", null);

        String query = uri.getRawQuery();
        assertEquals(true, query.contains("serviceKey=abc%2Bdef%2Fghi%3D"));
        assertEquals(false, query.contains("%252B"));
        assertEquals(true, query.contains("owner_nm=%ED%99%8D%EA%B8%B8%EB%8F%99"));
    }

    @Test
    void should_acceptResponse_when_resultCodeIsSuccess() {
        assertDoesNotThrow(() -> client.validateHeader(Map.of(
                "resultCode", "00",
                "resultMsg", "NORMAL SERVICE."
        )));
    }

    @Test
    void should_throwBadRequest_when_registrationQueryIsInvalid() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.validateHeader(Map.of(
                        "resultCode", "10",
                        "resultMsg", "INVALID REQUEST PARAMETER ERROR."
                )));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("일치하는 동물등록정보를 찾을 수 없습니다.", exception.getMessage());
    }

    @Test
    void should_throwBadRequest_when_registrationIsNotFound() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.validateHeader(Map.of(
                        "resultCode", "03",
                        "resultMsg", "NO DATA."
                )));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void should_throwBadGateway_when_externalServiceFails() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.validateHeader(Map.of(
                        "resultCode", "05",
                        "resultMsg", "SERVICETIMEOUT ERROR."
                )));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
    }
}
