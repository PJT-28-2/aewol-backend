package com.aewol.domain.transaction.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.transaction.dto.PaymentRequest;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 경로의 트랜잭션 경계를 지킨다.
 *
 * <p>느린 외부 호출을 트랜잭션 안에 두면 그동안 DB 커넥션이 점유돼 풀이 고갈된다.
 * 팀은 이미 CODEF 사례(2026-08-06)로 한 번 겪었고 코드 여섯 곳에 주석을 남겼는데도
 * 결제 경로에서 같은 일이 반복됐다. 주석만으로는 안 막히므로 테스트로 고정한다.
 *
 * <p>재현 결과: 결제 10건 동시 + 카카오 20초 무응답일 때, 무관한 요청의 대기 시간이
 * 트랜잭션 안에서는 19.9초, 밖에서는 29ms였다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentTransactionBoundaryTest {

    @Mock TransactionMapper transactionMapper;
    @Mock WalletMapper walletMapper;
    @Mock AutoTaggingService autoTaggingService;
    @Mock PetMapper petMapper;

    private TransactionServiceImpl service() {
        return new TransactionServiceImpl(transactionMapper, walletMapper, autoTaggingService, petMapper,
                new PaymentLedgerService(transactionMapper, walletMapper));
    }

    /*
     * @Transactional이 다시 붙으면 커넥션이 외부 호출 내내 묶인다. 겉으로는 잘 도는 것처럼
     * 보이고 카카오가 느려질 때만 드러나므로, 붙는 즉시 여기서 걸리게 한다.
     */
    @Test
    @DisplayName("결제 진입점에 @Transactional이 없다")
    void should_notBeTransactional_atPaymentEntryPoint() throws Exception {
        Method processPayment = TransactionServiceImpl.class
                .getMethod("processPayment", String.class, PaymentRequest.class);

        assertNull(processPayment.getAnnotation(Transactional.class),
                "processPayment는 카카오 API를 호출하므로 트랜잭션을 열면 안 된다. "
                        + "원장 기록은 PaymentLedgerService.record가 맡는다.");
        assertNull(TransactionServiceImpl.class.getAnnotation(Transactional.class),
                "클래스에 붙이면 processPayment까지 트랜잭션이 걸린다.");
    }

    // 원장 기록은 반대로 반드시 트랜잭션이어야 한다. 잔액 차감과 거래 생성이 갈라지면
    // 돈만 빠지고 내역이 없는 상태가 된다.
    @Test
    @DisplayName("원장 기록은 트랜잭션 안에서 돈다")
    void should_beTransactional_atLedger() throws Exception {
        Method record = PaymentLedgerService.class
                .getMethod("record", com.aewol.domain.transaction.dto.PaymentRecordCommand.class, String.class);

        assertNotNull(record.getAnnotation(Transactional.class),
                "잔액 차감과 거래 생성은 한 트랜잭션이어야 한다.");
    }

    // 순서가 뒤집히면 외부 호출이 다시 트랜잭션 안으로 들어간다.
    @Test
    @DisplayName("카테고리 판정이 잔액 차감보다 먼저 끝난다")
    void should_finishExternalCall_beforeLedgerWork() {
        PaymentRequest request = new PaymentRequest();
        ReflectionTestUtils.setField(request, "merchantName", "처음보는가게");
        ReflectionTestUtils.setField(request, "amount", new BigDecimal("10000"));

        Map<String, Object> wallet = new HashMap<>();
        wallet.put("wallet_id", "wallet-1");
        wallet.put("balance", new BigDecimal("50000"));
        when(walletMapper.findByMemberId("member-1")).thenReturn(wallet);
        when(walletMapper.deductBalance(any(), any())).thenReturn(1);
        when(autoTaggingService.categorize("처음보는가게")).thenReturn("ETC");
        doAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("txnId", "txn-1");
            return 1;
        }).when(transactionMapper).insert(any());

        try {
            service().processPayment("member-1", request);
        } catch (RuntimeException ignored) {
            // 응답 조회까지는 검증 대상이 아니다.
        }

        InOrder order = inOrder(autoTaggingService, walletMapper, transactionMapper);
        order.verify(autoTaggingService).categorize("처음보는가게");
        order.verify(walletMapper).deductBalance(any(), any());
        order.verify(transactionMapper).insert(any());
    }
}
