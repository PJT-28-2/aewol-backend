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
}
