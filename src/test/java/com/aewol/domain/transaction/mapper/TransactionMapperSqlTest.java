package com.aewol.domain.transaction.mapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TransactionMapperSqlTest {

    @Test
    void should_persistRecurringId_when_insertingTransaction() throws Exception {
        String sql = Files.readString(
                Path.of("src/main/resources/mapper/transaction/TransactionMapper.xml"),
                StandardCharsets.UTF_8);
        int start = sql.indexOf("<insert id=\"insert\"");
        int end = sql.indexOf("</insert>", start);
        String insert = sql.substring(start, end);

        assertTrue(insert.contains("recurring_id"));
        assertTrue(insert.contains("#{recurringId}"));
    }

    @Test
    void should_includeRefundInChargeFilter_forFindByWalletId() throws Exception {
        assertTrue(selectBody("findByWalletId").contains("'DEPOSIT', 'REFUND'"));
    }

    @Test
    void should_includeRefundInChargeFilter_forFindRecentByWalletId() throws Exception {
        assertTrue(selectBody("findRecentByWalletId").contains("'DEPOSIT', 'REFUND'"));
    }

    @Test
    void should_excludeRefundedPayments_forFindByWalletIdPaymentFilter() throws Exception {
        String select = selectBody("findByWalletId");

        assertTrue(select.contains("txnFilter == 'PAYMENT'"));
        assertTrue(select.contains("t.txn_type = 'PAYMENT'"));
        assertTrue(select.contains("gpp.payment_status = 'CANCELLED'"));
    }

    @Test
    void should_excludeRefundedPayments_forFindByWalletIdWithdrawFilter() throws Exception {
        String withdrawBlock = ifBlock(selectBody("findByWalletId"), "WITHDRAW");

        assertTrue(withdrawBlock.contains("gpp.payment_status = 'CANCELLED'"));
    }

    @Test
    void should_excludeRefundedPayments_forFindRecentByWalletIdWithdrawFilter() throws Exception {
        String withdrawBlock = ifBlock(selectBody("findRecentByWalletId"), "WITHDRAW");

        assertTrue(withdrawBlock.contains("gpp.payment_status = 'CANCELLED'"));
    }

    private static String selectBody(String selectId) throws Exception {
        String sql = Files.readString(
                Path.of("src/main/resources/mapper/transaction/TransactionMapper.xml"),
                StandardCharsets.UTF_8);
        int start = sql.indexOf("<select id=\"" + selectId + "\"");
        int end = sql.indexOf("</select>", start);
        return sql.substring(start, end);
    }

    private static String ifBlock(String body, String txnFilterValue) {
        int start = body.indexOf("txnFilter == '" + txnFilterValue + "'");
        int end = body.indexOf("</if>", start);
        return body.substring(start, end);
    }
}
