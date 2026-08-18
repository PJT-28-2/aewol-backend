package com.aewol.domain.insight.service.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MonthPaceTest {

    // 8월은 31일이다. 10일에 10만 원이면 하루 1만 원, 월말 31만 원.
    @Test
    @DisplayName("지금 속도를 월말까지 늘려 예상치를 낸다")
    void should_projectMonthEnd_when_enoughDaysElapsed() {
        BigDecimal projected =
                MonthPace.projectMonthEnd(new BigDecimal("100000"), LocalDate.of(2026, 8, 10));

        assertEquals(new BigDecimal("310000"), projected);
    }

    // 1일에 5만 원을 썼다고 월말 155만 원이라 말하면 거의 확실히 틀린다.
    @Test
    @DisplayName("달 초에는 표본이 모자라 추정하지 않는다")
    void should_returnNull_when_tooEarlyInMonth() {
        assertNull(MonthPace.projectMonthEnd(new BigDecimal("50000"), LocalDate.of(2026, 8, 1)));
        assertNull(MonthPace.projectMonthEnd(new BigDecimal("50000"), LocalDate.of(2026, 8, 4)));
    }

    // 말일에는 예상치가 실제값과 같아져 '예상'이라 부를 것이 없다.
    @Test
    @DisplayName("말일에는 예측하지 않는다")
    void should_returnNull_when_lastDayOfMonth() {
        assertNull(MonthPace.projectMonthEnd(new BigDecimal("100000"), LocalDate.of(2026, 8, 31)));
    }

    @Test
    @DisplayName("쌓인 값이 없으면 예측하지 않는다")
    void should_returnNull_when_nothingAccumulated() {
        assertNull(MonthPace.projectMonthEnd(BigDecimal.ZERO, LocalDate.of(2026, 8, 10)));
        assertNull(MonthPace.projectMonthEnd(null, LocalDate.of(2026, 8, 10)));
    }

    @Test
    @DisplayName("남은 일수는 오늘을 뺀 값이다")
    void should_countRemainingDaysExcludingToday() {
        assertEquals(21, MonthPace.remainingDays(LocalDate.of(2026, 8, 10)));
        assertEquals(0, MonthPace.remainingDays(LocalDate.of(2026, 8, 31)));
    }
}
