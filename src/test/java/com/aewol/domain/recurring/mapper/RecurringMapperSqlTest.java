package com.aewol.domain.recurring.mapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RecurringMapperSqlTest {

    @Test
    void should_lockRecurringRow_when_selectingForBatchExecution() throws Exception {
        String sql = Files.readString(
                Path.of("src/main/resources/mapper/recurring/RecurringMapper.xml"),
                StandardCharsets.UTF_8);
        int start = sql.indexOf("<select id=\"findByIdForUpdate\"");
        int end = sql.indexOf("</select>", start);
        String select = sql.substring(start, end).toUpperCase();

        assertTrue(select.contains("FOR UPDATE"));
    }
}
