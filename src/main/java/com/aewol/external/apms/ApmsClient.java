package com.aewol.external.apms;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import com.aewol.common.exception.BusinessException;

/**
 * 공공데이터포털 동물등록정보 조회 API(1543061/animalInfoSrvc_v3) 클라이언트.
 *
 * <p>dog_reg_no(동물등록번호) + owner_nm(소유자 성명)으로 등록 정보를 단건 조회한다.
 * 신원 정보만으로 후보를 검색하는 API가 아니라, 등록번호가 필수 파라미터인 상세조회 API다.
 * owner_birth도 대체 파라미터로 쓸 수 있지만 YYMMDD(6자리) 형식이 필요해서(예:
 * "1990-01-01"을 그대로 보내면 INVALID_REQUEST_PARAMETER_ERROR) 포맷 변환 부담을 피하려고
 * owner_nm만 사용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApmsClient {

    // "1543061/animalInfoSrvc_v3"는 서비스명일 뿐이고, 실제 호출에는 상세기능
    // 구분코드 "animalInfo_v3"를 마지막에 붙여야 한다. 이게 빠지면 공공데이터포털이
    // "없는 서비스입니다(NO_OPENAPI_SERVICE_ERROR)"를 반환한다.
    private static final String BASE_URL = "https://apis.data.go.kr/1543061/animalInfoSrvc_v3/animalInfo_v3";
    private static final String RESULT_CODE_SUCCESS = "00";

    private final RestTemplate restTemplate;

    @Value("${external.apms.service-key:}")
    private String serviceKey;

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /**
     * 동물등록번호 + 소유자 정보로 등록 정보를 단건 조회한다.
     * 조회 결과가 없으면 {@link Optional#empty()}를 반환한다.
     *
     * @return APMS 원본 응답의 item 필드(dogRegNo, rfidCd, rfidGubun, dogNm, birthDt,
     *         sexNm, kindNm, neuterYn, orgNm, aprGbNm, regTm, aprTm 등 원본 키 그대로)
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> findRegistration(String regNumber, String ownerName) {
        if (!isConfigured()) {
            log.warn("APMS service-key가 설정되지 않아 조회를 건너뜁니다.");
            return Optional.empty();
        }

        Map<String, Object> body;
        try {
            body = restTemplate.getForObject(buildUri(regNumber, ownerName), Map.class);
        } catch (RestClientException e) {
            log.error("APMS 동물등록정보 조회 실패 - message={}", e.getMessage());
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "동물등록정보 조회 서비스에 일시적으로 접속할 수 없습니다.");
        }
        if (body == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "동물등록정보 조회 서비스가 빈 응답을 반환했습니다.");
        }

        Object rawResponse = body.get("response");
        if (!(rawResponse instanceof Map)) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "동물등록정보 조회 응답 형식이 올바르지 않습니다.");
        }
        Map<String, Object> response = (Map<String, Object>) rawResponse;

        Object rawHeader = response.get("header");
        Map<String, Object> header = rawHeader instanceof Map ? (Map<String, Object>) rawHeader : null;
        String resultCode = header == null ? null : String.valueOf(header.get("resultCode"));
        if (!RESULT_CODE_SUCCESS.equals(resultCode)) {
            String resultMsg = header == null ? "알 수 없는 오류" : String.valueOf(header.get("resultMsg"));
            log.warn("APMS 동물등록정보 조회 결과 이상 - resultCode={}, resultMsg={}", resultCode, resultMsg);
            return Optional.empty();
        }

        // 조회 결과가 없으면 body가 빈 문자열("")로 내려오는 경우가 있어 Map이 아닐 수 있다.
        Object rawBody = response.get("body");
        if (!(rawBody instanceof Map)) {
            return Optional.empty();
        }
        Object item = ((Map<String, Object>) rawBody).get("item");
        if (!(item instanceof Map)) {
            return Optional.empty();
        }
        return Optional.of((Map<String, Object>) item);
    }

    private URI buildUri(String regNumber, String ownerName) {
        // serviceKey는 발급 시점에 이미 URL 인코딩된 형태라 재인코딩하면 서명이 깨진다 (Gov24Client와 동일 주의사항).
        String encoded = UriComponentsBuilder.fromHttpUrl(BASE_URL)
                .queryParam("dog_reg_no", regNumber)
                .queryParam("owner_nm", ownerName)
                .queryParam("_type", "json")
                .encode(StandardCharsets.UTF_8)
                .toUriString();
        return URI.create(encoded + "&serviceKey=" + serviceKey);
    }
}
