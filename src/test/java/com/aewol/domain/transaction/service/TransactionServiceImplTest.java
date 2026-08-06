package com.aewol.domain.transaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.aewol.domain.transaction.dto.PaymentRequest;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.common.exception.BusinessException;
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
import org.springframework.http.HttpStatus;

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
        when(walletMapper.findById("wallet-1")).thenReturn(map("member_id", "member-1"));
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

    @Test
    void should_returnTransaction_when_memberOwnsWallet() {
        TransactionServiceImpl service = service();
        when(transactionMapper.findById("txn-1")).thenReturn(transaction("wallet-1", null));
        when(walletMapper.findById("wallet-1")).thenReturn(map("member_id", "member-1"));

        assertEquals("txn-1", service.getTransaction("member-1", "txn-1").getTxnId());
    }

    @Test
    void should_throwNotFound_when_transactionDoesNotExist() {
        TransactionServiceImpl service = service();
        when(transactionMapper.findById("txn-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getTransaction("member-1", "txn-404"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void should_throwForbidden_when_memberCannotViewTransaction() {
        TransactionServiceImpl service = service();
        when(transactionMapper.findById("txn-1")).thenReturn(transaction("wallet-1", "pet-1"));
        when(walletMapper.findById("wallet-1")).thenReturn(map("member_id", "owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getTransaction("member-2", "txn-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    @Test
    void should_throwForbidden_when_nonOwnerViewsTransactionWithoutPet() {
        TransactionServiceImpl service = service();
        when(transactionMapper.findById("txn-1")).thenReturn(transaction("wallet-1", null));
        when(walletMapper.findById("wallet-1")).thenReturn(map("member_id", "owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getTransaction("member-2", "txn-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    private TransactionServiceImpl service() {
        return new TransactionServiceImpl(transactionMapper, walletMapper, autoTaggingService);
    }

    private static Map<String, Object> transaction(String walletId, String petId) {
        return map("txn_id", "txn-1", "wallet_id", walletId, "pet_id", petId,
                "txn_type", "PAYMENT", "price", BigDecimal.TEN,
                "auto_tagged", "N", "txn_date", LocalDateTime.now());
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
