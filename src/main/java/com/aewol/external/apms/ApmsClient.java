package com.aewol.external.apms;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApmsClient {

    private final RestTemplate restTemplate;

    /**
     * 동물등록번호 조회 (APMS 공공 API)
     */
    public Object verifyRegistration(String regNumber) {
        // TODO: APMS 반려동물등록 API 구현
        log.info("APMS 등록번호 조회 - regNumber: {}", regNumber);
        return null;
    }
}
