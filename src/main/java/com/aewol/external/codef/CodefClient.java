package com.aewol.external.codef;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class CodefClient {

    private final RestTemplate restTemplate;

    @Value("${external.codef.client-id:}")
    private String clientId;

    @Value("${external.codef.client-secret:}")
    private String clientSecret;

    /**
     * CODEF 계좌 연동 (Connected ID 발급)
     */
    public String createConnectedId(String bankCode, String accountNumber) {
        // TODO: CODEF API 연동 구현
        log.info("CODEF 계좌 연동 요청 - bankCode: {}, account: {}", bankCode, accountNumber);
        return "connected-id-placeholder";
    }

    /**
     * 계좌 잔액 조회
     */
    public Object getAccountBalance(String connectedId, String bankCode, String accountNumber) {
        // TODO: CODEF 잔액 조회 API 구현
        log.info("CODEF 잔액 조회 - connectedId: {}", connectedId);
        return null;
    }
}
