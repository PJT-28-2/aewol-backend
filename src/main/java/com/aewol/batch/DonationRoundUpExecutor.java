package com.aewol.batch;

import com.aewol.common.exception.BusinessException;
import com.aewol.domain.donation.PotTransfer;
import com.aewol.domain.donation.mapper.DonationMapper;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 1명의 애월지갑(MAIN) 잔액을 저금 단위로 절삭해 저금통(DONATION)으로 옮긴다.
 *
 * <p>배치 메서드에서 self-invocation으로 @Transactional을 붙이면 프록시가 적용되지 않으므로 별도 빈으로 분리
 * — GroupPurchaseRefundExecutor/RecurringPaymentExecutor와 동일 패턴. 한 회원의 실패가 같은 배치
 * 실행의 다른 회원 처리를 롤백시키지 않는다.
 *
 * <p>잠금 순서는 MAIN → DONATION → donation_setting 으로, 월말 자동기부와 같다.
 * 설정은 먼저 일반 조회한 뒤 지갑을 잠그고 다시 잠금 조회·검증한다.
 */
@Component
@RequiredArgsConstructor
public class DonationRoundUpExecutor {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final DonationMapper donationMapper;

    /**
     * @return 나머지를 실제로 저금통으로 옮기면 true, 처리할 게 없어(이미 오늘 처리, 나머지 0,
     * 유효하지 않은 값) 건너뛰면 false
     */
    @Transactional
    public boolean execute(Map<String, Object> candidate) {
        String memberId = text(candidate, "memberId", "member_id");
        if (memberId == null || memberId.isBlank()) {
            return false;
        }

        LocalDate today = LocalDate.now(SEOUL);
        Map<String, Object> peeked = donationMapper.findSettings(memberId);
        if (peeked == null || !bool(peeked, "piggyBankEnabled", "piggy_bank_enabled")) {
            return false;
        }
        if (alreadyTrimmedOn(peeked, today)) {
            return false;
        }
        BigDecimal peekedUnit = decimal(peeked, "savingUnit", "saving_unit");
        if (peekedUnit.signum() <= 0) {
            return false;
        }

        Map<String, Object> mainWallet = donationMapper.findMainWalletForUpdate(memberId);
        if (mainWallet == null) {
            return false;
        }
        Map<String, Object> pot = getOrCreatePotForUpdate(memberId);

        Map<String, Object> settings = donationMapper.findSettingsForUpdate(memberId);
        if (settings == null || !bool(settings, "piggyBankEnabled", "piggy_bank_enabled")) {
            return false;
        }
        if (alreadyTrimmedOn(settings, today)) {
            return false;
        }
        BigDecimal savingUnit = decimal(settings, "savingUnit", "saving_unit");
        if (savingUnit.signum() <= 0) {
            return false;
        }

        BigDecimal remainder = truncatedRemainder(decimal(mainWallet, "balance"), savingUnit);
        if (remainder.signum() <= 0) {
            donationMapper.markSpareTrimmed(memberId, today);
            return false;
        }

        String mainWalletId = text(mainWallet, "wallet_id", "walletId");
        String potWalletId = text(pot, "wallet_id", "walletId");
        if (donationMapper.decreaseMainWalletBalance(mainWalletId, remainder) != 1
                || donationMapper.increasePotBalance(potWalletId, remainder) != 1) {
            throw BusinessException.conflict("자투리 이체를 반영하지 못했습니다.");
        }

        Map<String, Object> transaction = new HashMap<>();
        transaction.put("sourceWalletId", mainWalletId);
        transaction.put("counterWalletId", potWalletId);
        transaction.put("amount", remainder);
        transaction.put("memo", "짜투리 저금통 절삭");
        transaction.put("idempotencyKey", PotTransfer.spareTrimKey(memberId, today));
        transaction.put("transferPurpose", PotTransfer.PURPOSE_SPARE_TRIM);
        donationMapper.insertWalletTransaction(transaction);

        if (donationMapper.markSpareTrimmed(memberId, today) != 1) {
            throw BusinessException.conflict("자투리 절삭 상태를 반영하지 못했습니다.");
        }
        return true;
    }

    /**
     * 지갑 잔액을 저금 단위로 깎고 남는 나머지.
     *
     * <p>31,275원을 1,000원 단위로 깎으면 275원이 저금통으로 가고 지갑에는 31,000원이 남는다.
     * 딱 떨어지면 옮길 것이 없으므로 0이다.
     */
    static BigDecimal truncatedRemainder(BigDecimal balance, BigDecimal savingUnit) {
        if (balance == null || savingUnit == null || balance.signum() <= 0 || savingUnit.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return balance.remainder(savingUnit);
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

    private static boolean alreadyTrimmedOn(Map<String, Object> settings, LocalDate today) {
        LocalDate last = localDate(value(settings, "lastSpareTrimmedOn", "last_spare_trimmed_on"));
        return last != null && !last.isBefore(today);
    }

    private static boolean bool(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        return value instanceof Boolean ? (Boolean) value
                : value instanceof Number ? ((Number) value).intValue() == 1
                : "Y".equalsIgnoreCase(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static LocalDate localDate(Object value) {
        if (value instanceof LocalDate) return (LocalDate) value;
        if (value instanceof java.util.Date) {
            return new Date(((java.util.Date) value).getTime()).toLocalDate();
        }
        if (value == null) return null;
        String text = String.valueOf(value);
        if (text.length() >= 10) {
            return LocalDate.parse(text.substring(0, 10));
        }
        return null;
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
