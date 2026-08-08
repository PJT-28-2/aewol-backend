package com.aewol.external.apms;

import com.aewol.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApmsClientTest {

    private ApmsClient client(RestTemplate restTemplate, String serviceKey) {
        ApmsClient client = new ApmsClient(restTemplate);
        ReflectionTestUtils.setField(client, "serviceKey", serviceKey);
        ReflectionTestUtils.setField(client, "baseUrl", "https://example.test/animalInfo_v3");
        return client;
    }

    @Test
    void should_returnEmpty_when_serviceKeyNotConfigured() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ApmsClient client = client(restTemplate, "");

        Optional<Map<String, Object>> result =
                client.findRegistration("410000019876543", "김애월");

        assertTrue(result.isEmpty());
        verify(restTemplate, never()).getForObject(any(URI.class), eq(String.class));
    }

    @Test
    void should_returnItem_when_resultCodeSuccess() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ApmsClient client = client(restTemplate, "encoded-key");
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn("""
                {"response":{"header":{"resultCode":"00"},"body":{"item":{
                "dogRegNo":"410000019876543","dogNm":"소로"}}}}
                """);

        Optional<Map<String, Object>> result =
                client.findRegistration("410000019876543", "김애월");

        assertTrue(result.isPresent());
        assertEquals("소로", result.get().get("dogNm"));
    }

    @Test
    void should_returnEmpty_when_registrationIsNotFound() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ApmsClient client = client(restTemplate, "encoded-key");
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn("""
                {"response":{"header":{"resultCode":"03","resultMsg":"NO DATA."}}}
                """);

        assertTrue(client.findRegistration("410000019876543", "김애월").isEmpty());
    }

    @Test
    void should_returnEmpty_when_bodyIsBlankString() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ApmsClient client = client(restTemplate, "encoded-key");
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn("""
                {"response":{"header":{"resultCode":"00"},"body":""}}
                """);

        assertTrue(client.findRegistration("410000019876543", "김애월").isEmpty());
    }

    @Test
    void should_throwBusinessException_when_httpCallFails() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ApmsClient client = client(restTemplate, "encoded-key");
        when(restTemplate.getForObject(any(URI.class), eq(String.class)))
                .thenThrow(new RestClientException("APMS 장애"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.findRegistration("410000019876543", "김애월"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
    }

    @Test
    void should_encodeServiceKeyOnce_when_encodedServiceKeyIsConfigured() {
        ApmsClient client = client(null, "abc%2Bdef%2Fghi%3D");

        URI uri = client.buildRequestUri("123456789012", "홍길동", null);

        String query = uri.getRawQuery();
        assertTrue(query.contains("serviceKey=abc%2Bdef%2Fghi%3D"));
        assertTrue(!query.contains("%252B"));
        assertTrue(query.contains("owner_nm=%ED%99%8D%EA%B8%B8%EB%8F%99"));
    }

    @Test
    void should_acceptResponse_when_resultCodeIsSuccess() {
        ApmsClient client = client(null, "key");

        assertDoesNotThrow(() -> client.validateHeader(Map.of(
                "resultCode", "00",
                "resultMsg", "NORMAL SERVICE."
        )));
    }

    @Test
    void should_throwBadRequest_when_registrationQueryIsInvalid() {
        ApmsClient client = client(null, "key");

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
        ApmsClient client = client(null, "key");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.validateHeader(Map.of(
                        "resultCode", "03",
                        "resultMsg", "NO DATA."
                )));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void should_throwBadGateway_when_externalServiceFails() {
        ApmsClient client = client(null, "key");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.validateHeader(Map.of(
                        "resultCode", "05",
                        "resultMsg", "SERVICETIMEOUT ERROR."
                )));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
    }
}
