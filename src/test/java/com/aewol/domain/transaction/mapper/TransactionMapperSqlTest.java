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
        String sql = Files.readString(
                Path.of("src/main/resources/mapper/transaction/TransactionMapper.xml"),
                StandardCharsets.UTF_8);
        int start = sql.indexOf("<select id=\"findByWalletId\"");
        int end = sql.indexOf("</select>", start);
        String select = sql.substring(start, end);

        assertTrue(select.contains("'DEPOSIT', 'REFUND'"));
    }

    @Test
    void should_includeRefundInChargeFilter_forFindRecentByWalletId() throws Exception {
        String sql = Files.readString(
                Path.of("src/main/resources/mapper/transaction/TransactionMapper.xml"),
                StandardCharsets.UTF_8);
        int start = sql.indexOf("<select id=\"findRecentByWalletId\"");
        int end = sql.indexOf("</select>", start);
        String select = sql.substring(start, end);

        assertTrue(select.contains("'DEPOSIT', 'REFUND'"));
    }

    @Test
    void should_excludeRefundedPayments_forFindByWalletIdPaymentFilter() throws Exception {
        String sql = Files.readString(
                Path.of("src/main/resources/mapper/transaction/TransactionMapper.xml"),
                StandardCharsets.UTF_8);
        int start = sql.indexOf("<select id=\"findByWalletId\"");
        int end = sql.indexOf("</select>", start);
        String select = sql.substring(start, end);

        assertTrue(select.contains("txnFilter == 'PAYMENT'"));
        assertTrue(select.contains("t.txn_type = 'PAYMENT'"));
        assertTrue(select.contains("gpp.payment_status = 'CANCELLED'"));
    }
}
