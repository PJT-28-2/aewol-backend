package com.aewol.domain.donation;

import java.time.LocalDate;

/**
 * 저금통 내부 이체의 작업 유형과 멱등키 네임스페이스.
 *
 * <p>transaction.idempotency_key는 (wallet_id, key) 유니크라, 수동 넣기가 클라이언트 키를
 * 그대로 저장하면 자동 절삭 키 {@code spare-trim-{memberId}-{date}}와 충돌한다.
 * 조회도 MAIN+TRANSFER+key뿐이면 절삭 거래를 넣기 결과로 오인한다.
 */
public final class PotTransfer {

    public static final String PURPOSE_DEPOSIT = "POT_DEPOSIT";
    public static final String PURPOSE_WITHDRAW = "POT_WITHDRAW";
    public static final String PURPOSE_SPARE_TRIM = "SPARE_TRIM";

    public static final String DEPOSIT_KEY_PREFIX = "pot-deposit:";
    public static final String WITHDRAW_KEY_PREFIX = "pot-withdraw:";

    private PotTransfer() {
    }

    public static String depositKey(String clientKey) {
        return DEPOSIT_KEY_PREFIX + clientKey;
    }

    public static String withdrawKey(String clientKey) {
        return WITHDRAW_KEY_PREFIX + clientKey;
    }

    public static String spareTrimKey(String memberId, LocalDate today) {
        return "spare-trim-" + memberId + "-" + today;
    }
}
