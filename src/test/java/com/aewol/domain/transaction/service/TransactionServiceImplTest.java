package com.aewol.domain.transaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.aewol.domain.transaction.dto.PaymentRequest;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TransactionServiceImplTest {

    @Mock TransactionMapper transactionMapper;
    @Mock WalletMapper walletMapper;
    @Mock AutoTaggingService autoTaggingService;

    @Test
    @DisplayName("반려동물을 선택해 결제하면 거래에 반려동물 태그를 직접 저장한다")
    void should_savePetId_when_paymentHasSelectedPet() {
        TransactionServiceImpl service = new TransactionServiceImpl(
                transactionMapper, walletMapper, autoTaggingService);
        PaymentRequest request = new PaymentRequest();
        ReflectionTestUtils.setField(request, "merchantName", "애월동물병원");
        ReflectionTestUtils.setField(request, "amount", new BigDecimal("72000"));
        ReflectionTestUtils.setField(request, "petId", "pet-1");
        when(walletMapper.findByMemberId("member-1")).thenReturn(map(
                "wallet_id", "wallet-1", "balance", new BigDecimal("100000")));
        when(autoTaggingService.categorize("애월동물병원")).thenReturn("HOSPITAL");
        when(transactionMapper.findById(any())).thenAnswer(invocation -> map(
                "txn_id", 1L, "wallet_id", "wallet-1",
                "pet_id", "pet-1", "txn_type", "PAYMENT",
                "price", new BigDecimal("72000"), "category", "HOSPITAL",
                "merchant_name", "애월동물병원", "auto_tagged", "Y",
                "txn_date", LocalDateTime.now()));

        service.processPayment("member-1", request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(transactionMapper).insert(captor.capture());
        assertEquals("pet-1", captor.getValue().get("petId"));
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
