package com.aewol.domain.insight.service.collector;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * "이 속도가 이어지면 이달 말에 얼마"를 계산한다.
 *
 * <p>카드마다 대상만 다를 뿐(지출, 적립) 계산은 같아서 한곳에 모았다.
 *
 * <p>추정은 관측 기간이 짧을수록 크게 흔들린다. 1일에 5만 원을 쓰면 월말 155만 원이
 * 되는 식이다. 그래서 며칠이 지나야 말이 되는지를 {@link #MIN_ELAPSED_DAYS}로 정해 두고,
 * 그 전에는 아예 추정하지 않는다. 근거가 약한 예측을 내놓느니 예측 줄을 빼는 편이 낫다.
 */
final class MonthPace {

    /** 이만큼은 지나야 일평균이 의미를 갖는다고 본다. */
    private static final int MIN_ELAPSED_DAYS = 5;

    private MonthPace() {
    }

    /**
     * 이번 달 말 예상치. 추정이 무의미한 시점이면 {@code null}.
     *
     * <p>말일에는 남은 날이 없어 예상치가 실제값과 같아진다. 그때도 {@code null}을 준다.
     */
    static BigDecimal projectMonthEnd(BigDecimal soFar, LocalDate today) {
        if (soFar == null || soFar.signum() <= 0) {
            return null;
        }
        int elapsed = today.getDayOfMonth();
        int total = today.lengthOfMonth();
        if (elapsed < MIN_ELAPSED_DAYS || elapsed >= total) {
            return null;
        }
        return soFar.multiply(BigDecimal.valueOf(total))
                .divide(BigDecimal.valueOf(elapsed), 0, RoundingMode.HALF_UP);
    }

    /** 오늘을 포함하지 않은 이번 달 잔여 일수. */
    static int remainingDays(LocalDate today) {
        return today.lengthOfMonth() - today.getDayOfMonth();
    }
}
