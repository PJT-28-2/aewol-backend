package com.aewol.external.apms;

import com.aewol.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 공공데이터포털 동물등록정보 조회 API(1543061/animalInfoSrvc_v3) 클라이언트.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApmsClient {

    private static final List<String> INVALID_REGISTRATION_RESULT_CODES = List.of("03", "10", "11");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${external.apms.service-key:}")
    private String serviceKey;

    @Value("${external.apms.base-url:https://apis.data.go.kr/1543061/animalInfoSrvc_v3/animalInfo_v3}")
    private String baseUrl;

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /**
     * 동물등록정보 동기화에서 사용하는 조회 메서드.
     * 설정이 없거나 등록정보가 일치하지 않으면 빈 결과를 반환한다.
     */
    public Optional<Map<String, Object>> findRegistration(String regNumber, String ownerName) {
        if (!isConfigured()) {
            log.warn("APMS service-key가 설정되지 않아 조회를 건너뜁니다.");
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(verifyRegistration(regNumber, ownerName, null));
        } catch (BusinessException e) {
            if (e.getStatus() == HttpStatus.BAD_REQUEST) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * 동물등록번호와 소유자 정보로 등록정보를 검증한다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyRegistration(String regNumber, String ownerName, String ownerBirth) {
        if (!isConfigured()) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "동물등록 조회 서비스가 설정되지 않았습니다.");
        }

        try {
            String raw = restTemplate.getForObject(
                    buildRequestUri(regNumber, ownerName, ownerBirth), String.class);
            Map<String, Object> root = objectMapper.readValue(raw, Map.class);
            Object responseObject = root.get("response");
            Map<String, Object> response = responseObject instanceof Map
                    ? (Map<String, Object>) responseObject
                    : root;
            validateHeader((Map<String, Object>) response.get("header"));
            return extractItem(response.get("body"));
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("동물등록 정보조회 API 호출 실패", e);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "동물등록정보 조회에 실패했습니다.");
        } catch (Exception e) {
            log.error("동물등록 정보조회 응답 처리 실패", e);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "동물등록정보 응답을 처리하지 못했습니다.");
        }
    }

    URI buildRequestUri(String regNumber, String ownerName, String ownerBirth) {
        StringBuilder query = new StringBuilder()
                .append("serviceKey=").append(encode(normalizeServiceKey(serviceKey)))
                .append("&dog_reg_no=").append(encode(regNumber))
                .append("&_type=json");
        if (ownerName != null && !ownerName.isBlank()) {
            query.append("&owner_nm=").append(encode(ownerName));
        }
        if (ownerBirth != null && !ownerBirth.isBlank()) {
            query.append("&owner_birth=").append(encode(toApmsBirth(ownerBirth)));
        }
        return URI.create(baseUrl + "?" + query);
    }

    /** APMS는 생년월일을 주민번호 앞 6자리(YYMMDD)로 받는다. 앱 내부는 yyyyMMdd(8자리)로 다루므로 전송 직전 변환한다. */
    private String toApmsBirth(String ownerBirth) {
        return ownerBirth.length() == 8 ? ownerBirth.substring(2) : ownerBirth;
    }

    void validateHeader(Map<String, Object> header) {
        if (header == null) return;
        String resultCode = stringValue(header.get("resultCode"));
        if (resultCode != null && !"00".equals(resultCode)) {
            log.warn("동물등록 정보조회 오류 - code: {}, message: {}", resultCode, header.get("resultMsg"));
            if (INVALID_REGISTRATION_RESULT_CODES.contains(resultCode)) {
                throw new BusinessException("일치하는 동물등록정보를 찾을 수 없습니다.");
            }
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "동물등록정보 조회에 실패했습니다.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractItem(Object bodyObject) {
        if (!(bodyObject instanceof Map)) return null;
        Map<String, Object> body = (Map<String, Object>) bodyObject;
        if (body.containsKey("dogRegNo") || body.containsKey("regNumber")) return body;
        Object item = body.get("item");
        if (item instanceof Map) return (Map<String, Object>) item;
        if (item instanceof List && !((List<?>) item).isEmpty() && ((List<?>) item).get(0) instanceof Map) {
            return (Map<String, Object>) ((List<?>) item).get(0);
        }
        Object items = body.get("items");
        if (items instanceof Map) return extractItem(items);
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String normalizeServiceKey(String key) {
        String trimmed = key.trim();
        return trimmed.contains("%")
                ? URLDecoder.decode(trimmed, StandardCharsets.UTF_8)
                : trimmed;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
