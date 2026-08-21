package com.aewol.domain.dashboard.mapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 이 클래스는 XML을 문자열로만 검증한다 — TransactionMapperTest(H2 통합 테스트)와 달리 실제 DB로
 * NOT EXISTS 로직을 실행해 보지는 못한다. DashboardMapper의 세 쿼리는 STR_TO_DATE/DATE_ADD(...,
 * INTERVAL 1 MONTH)라는 MySQL 전용 구문을 쓰는데, DATE_ADD의 "따옴표 없는 INTERVAL 1 MONTH"는
 * H2 파서가 그대로 거부한다("expected -, +, string") — CREATE ALIAS로 함수를 등록해도 우회할 수 없는
 * 파서 레벨 문법 차이라, 이 SQL을 고치지 않는 한 H2로는 검증 불가능하다. 실제 NOT EXISTS 동작은
 * 로컬 MySQL로 직접 확인했다(환불 전/후 /api/dashboard/summary·category 응답 비교).
 */
class DashboardMapperSqlTest {

    private static final String NOT_EXISTS_REFUND_CLAUSE =
            "SELECT 1 FROM group_purchase_participant gpp";
    private static final String CANCELLED_CONDITION = "gpp.payment_status = 'CANCELLED'";

    @Test
    void should_excludeRefundedPayments_forGetMonthlyTotal() throws Exception {
        String select = selectBody("getMonthlyTotal");

        assertTrue(select.contains(NOT_EXISTS_REFUND_CLAUSE));
        assertTrue(select.contains(CANCELLED_CONDITION));
    }

    @Test
    void should_excludeRefundedPayments_forGetPetMonthlySummary() throws Exception {
        String select = selectBody("getPetMonthlySummary");

        assertTrue(select.contains(NOT_EXISTS_REFUND_CLAUSE));
        assertTrue(select.contains(CANCELLED_CONDITION));
    }

    @Test
    void should_excludeRefundedPayments_forGetSpendingBreakdown() throws Exception {
        String select = selectBody("getSpendingBreakdown");

        assertTrue(select.contains(NOT_EXISTS_REFUND_CLAUSE));
        assertTrue(select.contains(CANCELLED_CONDITION));
    }

    private static String selectBody(String selectId) throws Exception {
        String sql = Files.readString(
                Path.of("src/main/resources/mapper/dashboard/DashboardMapper.xml"),
                StandardCharsets.UTF_8);
        int start = sql.indexOf("<select id=\"" + selectId + "\"");
        int end = sql.indexOf("</select>", start);
        return sql.substring(start, end);
    }
}
