package com.aewol.external.animalhospital;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class AnimalHospitalClientTest {

    @Mock RestTemplate restTemplate;
    @Mock Wgs84CoordinateConverter coordinateConverter;

    private AnimalHospitalClient client;

    @BeforeEach
    void setUp() {
        client = new AnimalHospitalClient(restTemplate, coordinateConverter);
        ReflectionTestUtils.setField(client, "serviceKey", "test-service-key");
        ReflectionTestUtils.setField(client, "baseUrl", "https://apis.data.go.kr/test/AnimalHospitalService");
    }

    private Map<String, Object> wrap(List<Map<String, Object>> items) {
        Map<String, Object> itemsWrapper = new HashMap<>();
        itemsWrapper.put("item", items);
        Map<String, Object> body = new HashMap<>();
        body.put("items", itemsWrapper);
        Map<String, Object> response = new HashMap<>();
        response.put("body", body);
        Map<String, Object> root = new HashMap<>();
        root.put("response", response);
        return root;
    }

    @Test
    @DisplayName("service-key가 없으면 외부 호출 없이 빈 목록을 반환한다")
    void should_returnEmptyList_when_notConfigured() {
        ReflectionTestUtils.setField(client, "serviceKey", "");

        List<Map<String, Object>> result = client.findAllHospitals();

        assertTrue(result.isEmpty());
        verify(restTemplate, never()).getForObject(any(URI.class), any());
    }

    @Test
    @DisplayName("응답 items 개수가 페이지 크기(100)보다 적으면 다음 페이지를 조회하지 않는다")
    void should_stopPaging_when_lastPageIsPartial() {
        List<Map<String, Object>> onePage = List.of(Map.of("MNG_NO", "1"), Map.of("MNG_NO", "2"));
        when(restTemplate.getForObject(any(URI.class), org.mockito.ArgumentMatchers.eq(Map.class)))
                .thenReturn(wrap(onePage));

        List<Map<String, Object>> result = client.findAllHospitals();

        assertEquals(2, result.size());
        verify(restTemplate, org.mockito.Mockito.times(1))
                .getForObject(any(URI.class), org.mockito.ArgumentMatchers.eq(Map.class));
    }

    @Test
    @DisplayName("페이지가 가득 차면 다음 페이지까지 이어서 수집한다")
    void should_collectNextPage_when_firstPageIsFull() {
        List<Map<String, Object>> fullPage = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            fullPage.add(Map.of("MNG_NO", String.valueOf(i)));
        }
        List<Map<String, Object>> lastPage = List.of(Map.of("MNG_NO", "100"));

        when(restTemplate.getForObject(any(URI.class), org.mockito.ArgumentMatchers.eq(Map.class)))
                .thenReturn(wrap(fullPage))
                .thenReturn(wrap(lastPage));

        List<Map<String, Object>> result = client.findAllHospitals();

        assertEquals(101, result.size());
        verify(restTemplate, org.mockito.Mockito.times(2))
                .getForObject(any(URI.class), org.mockito.ArgumentMatchers.eq(Map.class));
    }

    @Test
    @DisplayName("관리번호/이름/주소/전화 접근자는 필드명을 올바르게 읽는다")
    void should_readFields_fromRow() {
        Map<String, Object> row = Map.of(
                "MNG_NO", "508000001020260001",
                "BPLC_NM", "애월동물병원",
                "ROAD_NM_ADDR", "제주시 애월읍 애월로 1",
                "TELNO", "064-000-0000",
                "SALS_STTS_NM", "영업/정상");

        assertEquals("508000001020260001", client.mgtNo(row));
        assertEquals("애월동물병원", client.name(row));
        assertEquals("제주시 애월읍 애월로 1", client.address(row));
        assertEquals("064-000-0000", client.phone(row));
        assertEquals("영업/정상", client.statusName(row));
    }

    @Test
    @DisplayName("도로명주소가 없으면 지번주소로 대체한다")
    void should_fallBackToLotAddress_when_roadAddressMissing() {
        Map<String, Object> row = new HashMap<>();
        row.put("LOTNO_ADDR", "제주시 애월읍 1번지");

        assertEquals("제주시 애월읍 1번지", client.address(row));
    }

    @Test
    @DisplayName("[보안] RestClientException 메시지에 담긴 서비스키가 상위로 전파되지 않는다")
    void should_notLeakServiceKey_when_restCallFails() {
        // ResourceAccessException은 실제로 "I/O error on GET request for \"<URI>\": ..." 형태로
        // 요청 URI(서비스키 쿼리파라미터 포함)를 메시지에 그대로 담는다.
        String leakedSecret = "SECRET-KEY-VALUE";
        ResourceAccessException original = new ResourceAccessException(
                "I/O error on GET request for \"https://apis.data.go.kr/test/AnimalHospitalService"
                        + "?serviceKey=" + leakedSecret + "&pageNo=1\": Connection refused");
        when(restTemplate.getForObject(any(URI.class), org.mockito.ArgumentMatchers.eq(Map.class)))
                .thenThrow(original);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> client.findAllHospitals());

        assertFalse(thrown.getMessage().contains(leakedSecret),
                "예외 메시지에 서비스키가 노출되면 안 된다: " + thrown.getMessage());
        assertNull(thrown.getCause(),
                "cause를 원본 예외로 설정하면 상위 로거의 스택트레이스(Caused by)에 서비스키가 다시 노출된다");
        assertTrue(thrown.getMessage().contains("page=1"));
        assertTrue(thrown.getMessage().contains("ResourceAccessException"));
    }
}
