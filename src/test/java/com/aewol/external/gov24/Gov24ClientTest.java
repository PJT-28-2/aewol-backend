package com.aewol.external.gov24;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

class Gov24ClientTest {

    @Test
    void should_propagateHttpFailure_insteadOfReturningPartialData() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        Gov24Client client = new Gov24Client(restTemplate);
        ReflectionTestUtils.setField(client, "serviceKey", "encoded-key");
        when(restTemplate.getForObject(any(URI.class), eq(Map.class)))
                .thenThrow(new RestClientException("정부24 장애"));

        assertThrows(RestClientException.class,
                () -> client.findServicesByName("반려동물"));
    }
}
