package com.aewol.batch;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.grouppurchase.mapper.GroupPurchaseMapper;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 마감 미달 공동구매 참여자 1명을 독립된 트랜잭션으로 환불 처리한다.
 * (배치 메서드에서 self-invocation으로 @Transactional을 붙이면 프록시가 적용되지 않으므로 별도 빈으로 분리
 * — RecurringPaymentExecutor와 동일 패턴. 한 후보의 실패가 같은 배치 실행의 다른 후보 처리를 롤백시키지 않는다.)
 */
@Component
@RequiredArgsConstructor
public class GroupPurchaseRefundExecutor {

    private final GroupPurchaseMapper groupPurchaseMapper;
    private final WalletMapper walletMapper;
    private final TransactionMapper transactionMapper;

    /**
     * @return 환불 처리 성공 시 true, 동시에 leave()/작성자 취소로 이미 처리된 후보라 스킵 시 false
     */
    @Transactional
    public boolean execute(Map<String, Object> candidate) {
        String gpId = String.valueOf(candidate.get("gp_id"));
        String memberId = String.valueOf(candidate.get("member_id"));

        // cancelParticipant가 CANCELLED가 아닌 행만 대상으로 하므로, 이미 다른 경로로 처리된 후보는
        // 영향 행 0으로 감지되어 여기서 스킵된다(leave API와 동일한 가드).
        if (groupPurchaseMapper.cancelParticipant(gpId, memberId, LocalDateTime.now()) == 0) {
            return false;
        }

        int quantity = toInt(candidate.get("purchase_quantity"));
        groupPurchaseMapper.decreaseQuantityForExpired(gpId, quantity);

        BigDecimal refundedAmount = toBigDecimal(candidate.get("paid_amount"));
        if (refundedAmount != null) {
            Map<String, Object> gp = groupPurchaseMapper.findById(gpId);
            refundWallet(memberId, gpId, gp, refundedAmount);
        }
        return true;
    }

    /** 지갑 잔액을 환급하고 REFUND 타입 환불 거래내역을 생성한다. */
    private void refundWallet(String memberId, String gpId, Map<String, Object> gp, BigDecimal amount) {
        Map<String, Object> wallet = walletMapper.findByMemberId(memberId);
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다. memberId=" + memberId);
        }
        String walletId = String.valueOf(wallet.get("wallet_id"));
        if (walletMapper.addBalance(walletId, amount) == 0) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다. memberId=" + memberId);
        }

        Map<String, Object> txn = new HashMap<>();
        txn.put("walletId", walletId);
        txn.put("petId", null);
        txn.put("txnType", "REFUND");
        txn.put("price", amount);
        txn.put("category", toTxnCategory((String) gp.get("category")));
        txn.put("merchantName", gp.get("product_name"));
        txn.put("merchantCategoryCode", null);
        // 유저가 직접 취소한 게 아니라 마감 미달로 시스템이 자동 환불한 것이므로, leave()의
        // "참여 취소 환불" 문구와 구분되는 별도 문구를 쓴다(CS 문의 시 원인 구분용).
        txn.put("memo", "공동구매 마감 미달 자동환불: " + gp.get("product_name") + " (gpId=" + gpId + ")");
        txn.put("autoTagged", "N");
        txn.put("txnDate", LocalDateTime.now());
        transactionMapper.insert(txn);
    }

    /** GroupPurchaseServiceImpl#toTxnCategory와 동일한 분류 — 배치를 독립 트랜잭션으로 분리하며 함께 옮겨온 소규모 로직이라 중복을 그대로 둔다. */
    private static String toTxnCategory(String groupPurchaseCategory) {
        if ("사료".equals(groupPurchaseCategory) || "간식".equals(groupPurchaseCategory)) {
            return "FOOD";
        }
        return "ETC";
    }

    private static Integer toInt(Object value) {
        if (value == null) return null;
        return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(String.valueOf(value));
    }
}
