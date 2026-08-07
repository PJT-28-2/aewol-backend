package com.aewol.external.apms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.common.exception.BusinessException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

class ApmsClientTest {

    private ApmsClient client(RestTemplate restTemplate, String serviceKey) {
        ApmsClient client = new ApmsClient(restTemplate);
        ReflectionTestUtils.setField(client, "serviceKey", serviceKey);
        return client;
    }

    @Test
    void should_returnEmpty_when_serviceKeyNotConfigured() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ApmsClient client = client(restTemplate, "");

        Optional<Map<String, Object>> result = client.findRegistration("410000019876543", "김애월");

        assertTrue(result.isEmpty());
        verify(restTemplate, never()).getForObject(any(URI.class), eq(Map.class));
    }

    @Test
    void should_returnItem_when_resultCodeSuccess() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ApmsClient client = client(restTemplate, "encoded-key");
        Map<String, Object> item = Map.of("dogRegNo", "410000019876543", "dogNm", "소로");
        Map<String, Object> body = Map.of(
                "response", Map.of(
                        "header", Map.of("resultCode", "00", "resultMsg", "NORMAL SERVICE."),
                        "body", Map.of("item", item)));
        when(restTemplate.getForObject(any(URI.class), eq(Map.class))).thenReturn(body);

        Optional<Map<String, Object>> result = client.findRegistration("410000019876543", "김애월");

        assertTrue(result.isPresent());
        assertEquals("소로", result.get().get("dogNm"));
    }

    @Test
    void should_returnEmpty_when_resultCodeNotSuccess() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ApmsClient client = client(restTemplate, "encoded-key");
        Map<String, Object> body = Map.of(
                "response", Map.of(
                        "header", Map.of("resultCode", "30", "resultMsg", "SERVICE KEY IS NOT REGISTERED ERROR.")));
        when(restTemplate.getForObject(any(URI.class), eq(Map.class))).thenReturn(body);

        assertTrue(client.findRegistration("410000019876543", "김애월").isEmpty());
    }

    @Test
    void should_returnEmpty_when_bodyIsBlankString() {
        // 조회 결과가 없으면 body가 빈 문자열("")로 내려오는 공공데이터포털 특유의 케이스
        RestTemplate restTemplate = mock(RestTemplate.class);
        ApmsClient client = client(restTemplate, "encoded-key");
        Map<String, Object> body = Map.of(
                "response", Map.of(
                        "header", Map.of("resultCode", "00", "resultMsg", "NORMAL SERVICE."),
                        "body", ""));
        when(restTemplate.getForObject(any(URI.class), eq(Map.class))).thenReturn(body);

        assertTrue(client.findRegistration("410000019876543", "김애월").isEmpty());
    }

    @Test
    void should_throwBusinessException_when_httpCallFails() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ApmsClient client = client(restTemplate, "encoded-key");
        when(restTemplate.getForObject(any(URI.class), eq(Map.class)))
                .thenThrow(new RestClientException("APMS 장애"));

        assertThrows(BusinessException.class,
                () -> client.findRegistration("410000019876543", "김애월"));
    }
}
