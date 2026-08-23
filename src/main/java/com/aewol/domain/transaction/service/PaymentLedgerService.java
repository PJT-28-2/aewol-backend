package com.aewol.domain.transaction.service;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.transaction.dto.PaymentRecordCommand;
import com.aewol.domain.transaction.mapper.TransactionMapper;
import com.aewol.domain.wallet.mapper.WalletMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 원장 기록. 잔액 차감과 거래 생성만 한 트랜잭션으로 묶는다.
 *
 * <p><b>별도 빈으로 둔 이유</b>: {@code TransactionServiceImpl}은 카테고리 판정을 위해
 * 외부 HTTP를 호출하므로 트랜잭션을 걸면 안 된다. 그런데 {@code @Transactional}은 프록시
 * 경계를 넘는 호출에만 적용되므로, 같은 클래스의 private 헬퍼로 두면 트랜잭션이 걸리지
 * 않는다. {@code TossChargeService}와 {@code WalletService#depositExternal}이 같은 이유로
 * 나뉘어 있다.
 *
 * <p>여기에는 외부 호출을 넣지 않는다. 넣는 순간 이 분리가 의미를 잃는다.
 */
@Service
@RequiredArgsConstructor
public class PaymentLedgerService {

    private final TransactionMapper transactionMapper;
    private final WalletMapper walletMapper;

    /**
     * 잔액을 차감하고 거래를 기록한다.
     *
     * @param category 외부 호출로 미리 판정해 둔 카테고리
     * @return 생성된 거래 id
     */
    @Transactional
    public String record(PaymentRecordCommand command, String category) {
        Map<String, Object> wallet = walletMapper.findByMemberId(command.getMemberId());
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }
        String walletId = String.valueOf(wallet.get("wallet_id"));

        // 지갑 잔액 차감 (V1에서 버킷 폐기 — 지갑 단일 잔액)
        BigDecimal balance = (BigDecimal) wallet.get("balance");
        if (balance.compareTo(command.getAmount()) < 0) {
            throw new BusinessException("잔액이 부족합니다.");
        }
        // balance 조회 후 절대값을 저장하면 동시 결제에서 갱신이 유실될 수 있어,
        // balance - amount와 balance >= amount 조건을 하나의 원자적 UPDATE로 수행한다.
        if (walletMapper.deductBalance(walletId, command.getAmount()) == 0) {
            throw new BusinessException("잔액이 부족합니다.");
        }

        // 거래 기록 생성 — txn_id는 AUTO_INCREMENT 생성 키
        Map<String, Object> txn = new HashMap<>();
        txn.put("walletId", walletId);
        txn.put("petId", command.getPetId());
        txn.put("txnType", "PAYMENT");
        txn.put("price", command.getAmount());
        txn.put("category", category);
        txn.put("merchantName", command.getMerchantName());
        txn.put("merchantCategoryCode", null);
        txn.put("memo", command.getMemo());
        txn.put("autoTagged", "Y");
        txn.put("txnDate", LocalDateTime.now());
        transactionMapper.insert(txn);

        return String.valueOf(txn.get("txnId"));
    }
}
