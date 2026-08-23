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
     * 잔액을 차감하고 거래를 기록한 뒤, 방금 기록한 행을 돌려준다.
     *
     * <p><b>기록한 행을 읽는 것까지 이 트랜잭션 안에서 한다.</b> 커밋된 뒤에 읽으면, 그
     * 조회가 실패했을 때 돈은 빠지고 거래도 남았는데 사용자는 오류를 받는 상태가 된다.
     * 안에서 읽으면 실패가 곧 롤백이라 예전 동작과 같아진다.
     *
     * @param category 외부 호출로 미리 판정해 둔 카테고리
     * @return 방금 기록한 거래 행. 응답으로 옮기는 일은 부르는 쪽이 한다 — 그 변환은
     *         DB를 건드리지 않으므로 트랜잭션 밖에서 해도 안전하다.
     */
    @Transactional
    public Map<String, Object> record(PaymentRecordCommand command, String category) {
        Map<String, Object> wallet = walletMapper.findByMemberId(command.getMemberId());
        if (wallet == null) {
            throw BusinessException.notFound("지갑을 찾을 수 없습니다.");
        }
        String walletId = String.valueOf(wallet.get("wallet_id"));

        // 지갑 잔액 차감 (V1에서 버킷 폐기 — 지갑 단일 잔액)
        //
        // 아래 두 검사는 역할이 다르다. 이 검사는 방금 읽은 값으로 미리 걸러 실패를 빨리
        // 알리는 용도다. 동시에 결제가 들어오면 읽은 뒤 값이 바뀔 수 있어 이것만으로는
        // 부족하고, 실제 정합성은 그다음 원자적 UPDATE가 보장한다.
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

        String txnId = String.valueOf(txn.get("txnId"));
        Map<String, Object> saved = transactionMapper.findById(txnId);
        if (saved == null) {
            // 방금 넣은 행을 못 읽는다면 원장을 신뢰할 수 없는 상태다. 롤백시킨다.
            throw new IllegalStateException("방금 기록한 거래를 읽지 못했습니다. txnId=" + txnId);
        }
        return saved;
    }
}
