package com.aewol.batch;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.donation.mapper.DonationMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 잔돈 적립 후보 1건을 독립된 트랜잭션으로 처리한다.
 * (배치 메서드에서 self-invocation으로 @Transactional을 붙이면 프록시가 적용되지 않으므로 별도 빈으로 분리
 * — GroupPurchaseRefundExecutor/RecurringPaymentExecutor와 동일 패턴. 한 후보의 실패가 같은 배치 실행의
 * 다른 후보 처리를 롤백시키지 않는다. 로직 자체는 DonationServiceImpl#processDailyRoundUps에 있던 것을
 * 그대로 옮겼다 — findPotForUpdate의 FOR UPDATE 락이 배치 전체가 아니라 이 건 하나가 끝날 때까지만
 * 유지되는 게 이번 분리의 핵심이다.)
 */
@Component
@RequiredArgsConstructor
public class DonationRoundUpExecutor {

    private final DonationMapper donationMapper;

    /**
     * @return 잔돈이 실제로 저금통에 반영되면 true, 처리할 게 없어(중복 재실행, 딱 떨어지는 결제액,
     * 유효하지 않은 값) 건너뛰면 false
     */
    @Transactional
    public boolean execute(Map<String, Object> candidate) {
        BigDecimal paymentAmount = decimal(candidate, "amount");
        BigDecimal savingUnit = decimal(candidate, "savingUnit", "saving_unit");
        if (paymentAmount.signum() < 0 || savingUnit.signum() <= 0) {
            return false;
        }

        BigDecimal roundUpAmount = roundUpAmount(paymentAmount, savingUnit);
        Map<String, Object> pot = getOrCreatePotForUpdate(text(candidate, "memberId", "member_id"));
        String walletId = text(pot, "wallet_id", "walletId");
        Map<String, Object> roundUp = new HashMap<>();
        roundUp.put("sourceTxnId", text(candidate, "txnId", "txn_id"));
        roundUp.put("walletId", walletId);
        roundUp.put("savingUnit", savingUnit);
        roundUp.put("roundupAmount", roundUpAmount);
        roundUp.put("status", roundUpAmount.signum() == 0 ? "SKIPPED" : "PENDING");

        // insertRoundUp은 INSERT IGNORE라, 같은 source_txn_id로 이미 기록된 건이면(재실행 등)
        // 영향 행이 항상 0으로 감지되어 여기서 스킵된다 — 이게 이 배치의 멱등성을 지키는 핵심이다.
        // (ON DUPLICATE KEY UPDATE로 값이 그대로인 no-op을 흉내 내면, useAffectedRows를 별도
        // 설정하지 않은 이 프로젝트의 커넥터 기본값(found-rows 모드)에서는 그 경우도 1을 반환해
        // 중복 건을 신규 삽입으로 오인한다 — 실측으로 확인된 회귀라 INSERT IGNORE로 바꿨다.)
        // roundUpAmount가 0(딱 떨어지는 결제액)인 경우도 SKIPPED로 기록만 하고 잔액 반영 없이 스킵한다.
        if (donationMapper.insertRoundUp(roundUp) != 1 || roundUpAmount.signum() == 0) {
            return false;
        }
        // roundup_id는 AUTO_INCREMENT — useGeneratedKeys가 파라미터 맵에 채워준다
        if (donationMapper.increasePotBalance(walletId, roundUpAmount) != 1
                || donationMapper.completeRoundUp(text(roundUp, "roundupId")) != 1) {
            throw BusinessException.conflict("잔돈 적립을 반영하지 못했습니다.");
        }
        return true;
    }

    /**
     * 결제액을 적립 단위로 올렸을 때 생기는 차액.
     *
     * <p>34,800원을 1,000원 단위로 올리면 35,000원이고 차액은 200원이다. 이 200원이
     * 저금통에 들어간다. 딱 떨어지는 금액은 올릴 것이 없으므로 0을 준다(SKIPPED로 기록된다).
     */
    private static BigDecimal roundUpAmount(BigDecimal paymentAmount, BigDecimal savingUnit) {
        BigDecimal remainder = paymentAmount.remainder(savingUnit);
        return remainder.signum() == 0 ? BigDecimal.ZERO : savingUnit.subtract(remainder);
    }

    private Map<String, Object> getOrCreatePotForUpdate(String memberId) {
        getOrCreatePot(memberId);
        Map<String, Object> pot = donationMapper.findPotForUpdate(memberId);
        if (pot == null) throw BusinessException.notFound("저금통을 찾을 수 없습니다.");
        return pot;
    }

    private Map<String, Object> getOrCreatePot(String memberId) {
        Map<String, Object> pot = donationMapper.findPotByMemberId(memberId);
        if (pot != null) return pot;
        Map<String, Object> created = new HashMap<>();
        created.put("memberId", memberId);
        created.put("balance", BigDecimal.ZERO);
        donationMapper.insertPot(created);
        return donationMapper.findPotByMemberId(memberId);
    }

    private static Object value(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map != null && map.containsKey(key)) return map.get(key);
        return null;
    }

    private static String text(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal decimal(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        if (value == null) return BigDecimal.ZERO;
        return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(String.valueOf(value));
    }
}
