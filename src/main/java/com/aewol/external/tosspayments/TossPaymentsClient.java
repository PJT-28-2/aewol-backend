package com.aewol.external.tosspayments;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossPaymentsClient {

    private final RestTemplate restTemplate;

    /**
     * TossPayments 결제 승인 (테스트용)
     */
    public Object confirmPayment(String paymentKey, String orderId, long amount) {
        // TODO: TossPayments 결제 승인 API 구현
        log.info("TossPayments 결제 승인 요청 - paymentKey: {}, orderId: {}, amount: {}", paymentKey, orderId, amount);
        return null;
    }
}
