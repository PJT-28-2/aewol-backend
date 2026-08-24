package com.aewol.batch;

import com.aewol.domain.notification.service.InboxNotifier;
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
    private final InboxNotifier inboxNotifier;

    /**
     * @return 결제 성공 시 true, 이미 처리/해지됐거나 잔액 부족으로 스킵 시 false
     */
    @Transactional
    public boolean execute(Map<String, Object> due) {
        String recurringId = String.valueOf(due.get("recurring_id"));
        LocalDate today = LocalDate.now();

        // 최초 대상 조회 이후 다른 배치 인스턴스가 처리하거나 사용자가 해지할 수 있다.
        // 행 잠금을 획득한 뒤 DB 최신 상태를 다시 확인해 중복 차감과 해지 후 결제를 막는다.
        Map<String, Object> locked = recurringMapper.findByIdForUpdate(recurringId);
        if (!isDueAndActive(locked, today)) {
            return false;
        }

        String walletId = String.valueOf(locked.get("wallet_id"));
        BigDecimal price = toBigDecimal(locked.get("price"));
        int paymentDay = ((Number) locked.get("payment_day")).intValue();

        // 원자적 차감 — 조건을 만족하는 행이 없으면(잔액 부족) 0을 반환한다.
        // 잔액 부족 시 next_payment_date를 갱신하지 않아 다음날 배치가 자동으로 재시도한다.
        if (walletMapper.deductBalance(walletId, price) == 0) {
            return false;
        }

        // 거래 기록 생성 (정기결제는 카테고리가 이미 지정돼 있어 자동 태깅하지 않는다)
        Map<String, Object> txn = new HashMap<>();
        txn.put("walletId", walletId);
        txn.put("petId", locked.get("pet_id"));
        txn.put("recurringId", recurringId);
        txn.put("txnType", "PAYMENT");
        txn.put("price", price);
        txn.put("category", locked.get("category"));
        txn.put("merchantName", locked.get("product_name"));
        txn.put("merchantCategoryCode", null);
        txn.put("memo", "정기결제");
        txn.put("autoTagged", "N");
        txn.put("txnDate", LocalDateTime.now());
        transactionMapper.insert(txn);

        // 다음 주기 결제일로 갱신
        LocalDate next = RecurringServiceImpl.nextPaymentDate(paymentDay, today);
        recurringMapper.updateNextPaymentDate(recurringId, next);
        notifyPayment(walletId, locked, price);
        return true;
    }

    private void notifyPayment(String walletId, Map<String, Object> recurring, BigDecimal price) {
        Map<String, Object> wallet = walletMapper.findById(walletId);
        if (wallet == null || wallet.get("member_id") == null) return;
        inboxNotifier.notifyAfterCommit(
                String.valueOf(wallet.get("member_id")),
                InboxNotifier.Channel.PAYMENT,
                "PAYMENT",
                "정기결제가 실행됐어요",
                InboxNotifier.text(recurring.get("product_name"), "정기결제")
                        + " " + InboxNotifier.won(price) + "이 결제됐어요.",
                "/payment/recurring");
    }

    private static boolean isDueAndActive(Map<String, Object> recurring, LocalDate today) {
        if (recurring == null || !isActive(recurring.get("is_active"))) {
            return false;
        }
        Object nextPaymentDate = recurring.get("next_payment_date");
        return nextPaymentDate != null
                && !LocalDate.parse(String.valueOf(nextPaymentDate)).isAfter(today);
    }

    private static boolean isActive(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() == 1;
        return "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal) return (BigDecimal) value;
        return new BigDecimal(String.valueOf(value));
    }
}
