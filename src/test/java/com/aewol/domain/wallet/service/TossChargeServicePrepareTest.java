package com.aewol.domain.wallet.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.wallet.dto.TossChargeOrderRequest;
import com.aewol.domain.wallet.dto.TossChargeOrderResponse;
import com.aewol.domain.wallet.mapper.TossChargeOrderMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import com.aewol.external.tosspayments.TossPaymentAuditLogger;
import com.aewol.external.tosspayments.TossPaymentClaim;
import com.aewol.external.tosspayments.TossPaymentsClient;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TossChargeServicePrepareTest {

    private static final String MEMBER_ID = "member-1";

    @Mock TossPaymentClaim tossPaymentClaim;
    @Mock TossPaymentsClient tossPaymentsClient;
    @Mock TossPaymentAuditLogger auditLogger;
    @Mock WalletService walletService;
    @Mock WalletMapper walletMapper;
    @Mock TossChargeOrderMapper tossChargeOrderMapper;

    private TossChargeService service;

    @BeforeEach
    void setUp() {
        service = new TossChargeService(tossPaymentClaim, tossPaymentsClient, auditLogger,
                walletService, walletMapper, tossChargeOrderMapper);
    }

    @Test
    void should_returnOrderResponseWithValidUuid_when_walletExists() {
        stubWalletFound();

        TossChargeOrderResponse response = service.prepare(MEMBER_ID, request(new BigDecimal("5000")));

        assertNotNull(response.getOrderId());
        // 서버가 발급하는 orderId는 UUID 형식이어야 한다.
        UUID.fromString(response.getOrderId()); // UUID 파싱 실패 시 IllegalArgumentException
        assertEquals(new BigDecimal("5000"), response.getAmount());
    }

    @Test
    void should_insertOrderWithCorrectFields_when_walletExists() {
        stubWalletFound();

        TossChargeOrderResponse response = service.prepare(MEMBER_ID, request(new BigDecimal("10000")));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(tossChargeOrderMapper).insert(captor.capture());
        Map<String, Object> inserted = captor.getValue();

        assertEquals(response.getOrderId(), inserted.get("orderId"));
        assertEquals(MEMBER_ID, inserted.get("memberId"));
        assertEquals(new BigDecimal("10000"), inserted.get("amount"));
        assertEquals("PENDING", inserted.get("status"));
    }

    @Test
    void should_throwNotFound_when_walletDoesNotExist() {
        when(walletMapper.findByMemberId(MEMBER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.prepare(MEMBER_ID, request(new BigDecimal("5000"))));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(tossChargeOrderMapper, never()).insert(any());
    }

    private void stubWalletFound() {
        Map<String, Object> wallet = new HashMap<>();
        wallet.put("wallet_id", "wallet-1");
        wallet.put("member_id", MEMBER_ID);
        when(walletMapper.findByMemberId(MEMBER_ID)).thenReturn(wallet);
    }

    private TossChargeOrderRequest request(BigDecimal amount) {
        TossChargeOrderRequest req = new TossChargeOrderRequest();
        ReflectionTestUtils.setField(req, "amount", amount);
        return req;
    }
}
