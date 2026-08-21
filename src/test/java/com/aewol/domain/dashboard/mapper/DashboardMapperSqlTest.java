package com.aewol.domain.dashboard.mapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

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
