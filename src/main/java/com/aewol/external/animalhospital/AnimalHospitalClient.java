package com.aewol.external.animalhospital;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 행정안전부_동물_동물병원 조회서비스(data.go.kr, 서비스ID 15154952) 클라이언트.
 *
 * <p>요청 파라미터명, 응답 필드명, 페이지네이션 방식은 data.go.kr에 게시된 Swagger 명세
 * ({@code https://www.data.go.kr/data/15154952/openapi.do} 활용명세 탭)와 실제 서비스키로의
 * 라이브 호출({@code GET /info}, 200 응답, {@code totalCount} 확인)로 검증했다.
 *
 * <p>좌표계: 이 데이터는 Bessel 중부원점TM(EPSG:5174)으로 제공되므로,
 * {@link Wgs84CoordinateConverter}로 WGS84 변환을 거쳐야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnimalHospitalClient {

    private static final String DEFAULT_BASE_URL = "https://apis.data.go.kr/1741000/animal_hospitals/info";

    private static final int NUM_OF_ROWS = 100;
    /**
     * 페이지당 수집 상한 (무한 페이징 방지). 2026-08 기준 totalCount가 10,591건(약 106페이지)이므로
     * 여유를 두어 200페이지(최대 20,000건)까지 허용한다.
     */
    private static final int MAX_PAGES = 200;

    private static final String FIELD_MGT_NO = "MNG_NO";
    private static final String FIELD_NAME = "BPLC_NM";
    private static final String FIELD_ROAD_ADDRESS = "ROAD_NM_ADDR";
    private static final String FIELD_LOT_ADDRESS = "LOTNO_ADDR";
    private static final String FIELD_PHONE = "TELNO";
    private static final String FIELD_X = "CRD_INFO_X";
    private static final String FIELD_Y = "CRD_INFO_Y";
    private static final String FIELD_STATUS_NAME = "SALS_STTS_NM";

    private final RestTemplate restTemplate;
    private final Wgs84CoordinateConverter coordinateConverter;

    @Value("${external.animal-hospital.service-key:}")
    private String serviceKey;

    @Value("${external.animal-hospital.base-url:" + DEFAULT_BASE_URL + "}")
    private String baseUrl;

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /** 전국 동물병원 목록을 전부 조회한다. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> findAllHospitals() {
        if (!isConfigured()) {
            log.warn("animal-hospital service-key가 설정되지 않아 조회를 건너뜁니다.");
            return Collections.emptyList();
        }

        List<Map<String, Object>> collected = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            Map<String, Object> body;
            try {
                body = restTemplate.getForObject(buildUri(page), Map.class);
            } catch (RestClientException e) {
                // RestClientException(특히 ResourceAccessException)의 메시지에는 서비스키가 포함된
                // 요청 URI가 그대로 들어있다. 원본 예외를 그대로 던지면(cause로 감싸도 "Caused by"에
                // 메시지가 다시 노출된다) 상위 배치 잡의 catch(Exception) 로깅에서 새어나가므로,
                // 여기서 페이지 번호/예외 타입만 남긴 새 예외로 완전히 대체해서 던진다.
                log.error("동물병원 조회 실패 - page={}, exceptionType={}", page, e.getClass().getSimpleName());
                throw new IllegalStateException(
                        "동물병원 API 호출 실패 (page=" + page + ", exceptionType=" + e.getClass().getSimpleName() + ")");
            }
            if (body == null) {
                throw new IllegalStateException("동물병원 API가 빈 응답을 반환했습니다.");
            }

            List<Map<String, Object>> items = extractItems(body);
            collected.addAll(items);

            if (items.size() < NUM_OF_ROWS) return collected;
            if (page == MAX_PAGES) {
                throw new IllegalStateException("동물병원 API 조회가 최대 페이지에 도달해 불완전할 수 있습니다.");
            }
        }
        return collected;
    }

    /** 원본 좌표(X, Y, Bessel TM)를 WGS84 위경도로 변환한다. */
    public double[] toWgs84(Map<String, Object> row) {
        Double x = number(row, FIELD_X);
        Double y = number(row, FIELD_Y);
        if (x == null || y == null) {
            return null;
        }
        return coordinateConverter.toWgs84(x, y);
    }

    public String mgtNo(Map<String, Object> row) {
        return text(row, FIELD_MGT_NO);
    }

    public String name(Map<String, Object> row) {
        return text(row, FIELD_NAME);
    }

    public String address(Map<String, Object> row) {
        String road = text(row, FIELD_ROAD_ADDRESS);
        return road != null ? road : text(row, FIELD_LOT_ADDRESS);
    }

    public String phone(Map<String, Object> row) {
        return text(row, FIELD_PHONE);
    }

    public String statusName(Map<String, Object> row) {
        return text(row, FIELD_STATUS_NAME);
    }

    /**
     * 실응답 구조 {@code response.body.items.item}(item은 배열)을 파싱한다. 결과가 1건뿐이면
     * item이 배열이 아니라 단일 객체로 내려오는 data.go.kr의 흔한 케이스도 함께 처리한다.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractItems(Map<String, Object> body) {
        Object response = body.getOrDefault("response", body);
        if (!(response instanceof Map)) {
            throw new IllegalStateException("동물병원 API 응답 형식이 예상과 다릅니다: response 없음");
        }
        Object responseBody = ((Map<String, Object>) response).get("body");
        if (!(responseBody instanceof Map)) {
            throw new IllegalStateException("동물병원 API 응답 형식이 예상과 다릅니다: body 없음");
        }
        Object items = ((Map<String, Object>) responseBody).get("items");
        if (items == null || "".equals(items)) {
            return Collections.emptyList();
        }
        if (!(items instanceof Map)) {
            throw new IllegalStateException("동물병원 API 응답 형식이 예상과 다릅니다: items 객체 아님");
        }
        Object item = ((Map<String, Object>) items).get("item");
        if (item instanceof List) {
            return (List<Map<String, Object>>) item;
        }
        if (item instanceof Map) {
            return List.of((Map<String, Object>) item);
        }
        if (item == null) {
            return Collections.emptyList();
        }
        throw new IllegalStateException("동물병원 API 응답 형식이 예상과 다릅니다: item 배열/객체 아님");
    }

    private URI buildUri(int page) {
        return UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("serviceKey", serviceKey)
                .queryParam("pageNo", page)
                .queryParam("numOfRows", NUM_OF_ROWS)
                .queryParam("returnType", "json")
                .build(true)
                .toUri();
    }

    private static String text(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object value = map.get(key);
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static Double number(Map<String, Object> map, String key) {
        String s = text(map, key);
        if (s == null) return null;
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
