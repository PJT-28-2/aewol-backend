package com.aewol.batch;

import com.aewol.domain.recurring.mapper.RecurringMapper;
import com.aewol.domain.recurring.service.RecurringServiceImpl;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 정기결제 1건을 독립된 트랜잭션으로 처리한다.
 * (배치 메서드에서 self-invocation으로 @Transactional을 붙이면 프록시가 적용되지 않으므로 별도 빈으로 분리)
 */
@Component
@RequiredArgsConstructor
public class RecurringPaymentExecutor {

    private final WalletMapper walletMapper;
    private final TransactionMapper transactionMapper;
    private final RecurringMapper recurringMapper;

    /**
     * @return 결제 성공 시 true, 잔액 부족으로 스킵 시 false
     */
    @Transactional
    public boolean execute(Map<String, Object> due) {
        String recurringId = String.valueOf(due.get("recurring_id"));
        String walletId = String.valueOf(due.get("wallet_id"));
        BigDecimal price = toBigDecimal(due.get("price"));
        int paymentDay = ((Number) due.get("payment_day")).intValue();

        // 원자적 차감 — 조건을 만족하는 행이 없으면(잔액 부족) 0을 반환한다.
        // 잔액 부족 시 next_payment_date를 갱신하지 않아 다음날 배치가 자동으로 재시도한다.
        if (walletMapper.deductBalance(walletId, price) == 0) {
            return false;
        }

        // 거래 기록 생성 (정기결제는 카테고리가 이미 지정돼 있어 자동 태깅하지 않는다)
        Map<String, Object> txn = new HashMap<>();
        txn.put("walletId", walletId);
        txn.put("petId", due.get("pet_id"));
        txn.put("txnType", "PAYMENT");
        txn.put("price", price);
        txn.put("category", due.get("category"));
        txn.put("merchantName", due.get("product_name"));
        txn.put("merchantCategoryCode", null);
        txn.put("memo", "정기결제");
        txn.put("autoTagged", "N");
        txn.put("txnDate", LocalDateTime.now());
        transactionMapper.insert(txn);

        // 다음 주기 결제일로 갱신
        LocalDate next = RecurringServiceImpl.nextPaymentDate(paymentDay, LocalDate.now());
        recurringMapper.updateNextPaymentDate(recurringId, next);
        return true;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal) return (BigDecimal) value;
        return new BigDecimal(String.valueOf(value));
    }
}
