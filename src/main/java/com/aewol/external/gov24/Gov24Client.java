package com.aewol.external.gov24;

import java.net.URI;
import java.nio.charset.StandardCharsets;
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
 * 행정안전부 공공서비스(정부24) API 클라이언트.
 *
 * <p>제공 엔드포인트
 * <ul>
 *   <li>{@code /serviceList} — 서비스 목록 (21개 필드)</li>
 *   <li>{@code /serviceDetail} — 서비스 상세 (20개 필드)</li>
 *   <li>{@code /supportConditions} — 지원조건 (JA 코드 50여 개)</li>
 * </ul>
 *
 * <p><b>주의:</b> 이 API는 {@code cond[...::LIKE]} 필터를 적용해도 응답의
 * {@code totalCount}가 필터를 무시한 전체 건수(약 1만 건)를 그대로 반환한다.
 * 따라서 페이징 종료 판단에 {@code totalCount}를 쓰면 무한 루프가 된다.
 * 반드시 {@code data}가 비었거나 {@code perPage} 미만인지로 판단한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Gov24Client {

    private static final String BASE_URL = "https://api.odcloud.kr/api/gov24/v3";
    private static final int PER_PAGE = 100;
    /** 키워드 하나당 수집 상한 (무한 페이징 방지) */
    private static final int MAX_PAGES = 20;

    private final RestTemplate restTemplate;

    @Value("${external.gov24.service-key:}")
    private String serviceKey;

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /** 서비스명에 keyword가 포함된 목록을 모두 조회한다. */
    public List<Map<String, Object>> findServicesByName(String keyword) {
        return fetchAll("serviceList", "서비스명", keyword);
    }

    /** 서비스 상세를 서비스ID로 조회한다. 없으면 null. */
    public Map<String, Object> findServiceDetail(String serviceId) {
        List<Map<String, Object>> rows = fetchAll("serviceDetail", "서비스ID", serviceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 지원조건을 서비스ID로 조회한다. 없으면 null. */
    public Map<String, Object> findSupportConditions(String serviceId) {
        List<Map<String, Object>> rows = fetchAll("supportConditions", "서비스ID", serviceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchAll(String path, String field, String value) {
        if (!isConfigured()) {
            log.warn("정부24 service-key가 설정되지 않아 조회를 건너뜁니다. path={}", path);
            return Collections.emptyList();
        }

        List<Map<String, Object>> collected = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            Map<String, Object> body;
            try {
                body = restTemplate.getForObject(buildUri(path, field, value, page), Map.class);
            } catch (RestClientException e) {
                log.error("정부24 조회 실패 - path={}, field={}, page={}, message={}",
                        path, field, page, e.getMessage());
                throw e;
            }
            if (body == null) {
                throw new IllegalStateException("정부24 API가 빈 응답을 반환했습니다: " + path);
            }

            Object rawData = body.get("data");
            if (!(rawData instanceof List)) {
                throw new IllegalStateException("정부24 API 응답에 data 배열이 없습니다: " + path);
            }

            List<Map<String, Object>> data = (List<Map<String, Object>>) rawData;
            collected.addAll(data);

            // totalCount는 필터를 반영하지 않으므로 신뢰하지 않는다.
            if (data.size() < PER_PAGE) return collected;
            if (page == MAX_PAGES) {
                throw new IllegalStateException(
                        "정부24 API 조회가 최대 페이지에 도달해 불완전할 수 있습니다: " + path);
            }
        }
        return collected;
    }

    private URI buildUri(String path, String field, String value, int page) {
        // serviceKey는 발급 시점에 이미 URL 인코딩된 형태라 재인코딩하면 서명이 깨진다.
        // 나머지 파라미터만 인코딩한 뒤 serviceKey는 원문 그대로 덧붙인다.
        String encoded = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/" + path)
                .queryParam("page", page)
                .queryParam("perPage", PER_PAGE)
                .queryParam("cond[" + field + "::LIKE]", value)
                .encode(StandardCharsets.UTF_8)
                .toUriString();
        return URI.create(encoded + "&serviceKey=" + serviceKey);
    }
}
