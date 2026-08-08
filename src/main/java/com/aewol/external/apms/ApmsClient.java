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

    /**
     * 동물등록번호 조회 (APMS 공공 API)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyRegistration(String regNumber, String ownerName, String ownerBirth) {
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "동물등록 조회 서비스가 설정되지 않았습니다.");
        }

        try {
            // Encoding/Decoding 키 어느 쪽이 설정돼도 한 번만 인코딩된 URI를 만든다.
            // URI 오버로드를 사용해 RestTemplate의 URL 템플릿 재인코딩을 방지한다.
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
            query.append("&owner_birth=").append(encode(ownerBirth));
        }
        return URI.create(baseUrl + "?" + query);
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
        // 공공데이터포털 화면에 URL Encoding 키만 표시되는 경우가 있다. %2B, %2F,
        // %3D 등이 포함된 키만 한 번 디코딩하고, 원본 Base64 키의 '+'는 그대로 둔다.
        return trimmed.contains("%")
                ? URLDecoder.decode(trimmed, StandardCharsets.UTF_8)
                : trimmed;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
