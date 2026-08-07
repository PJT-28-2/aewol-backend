package com.aewol.domain.account.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AccountServiceImplTest는 AccountVerificationMapper를 mock으로 대체하기 때문에
 * incrementAttemptCount의 실제 SQL은 검증하지 못한다. XML 원문 문자열 검사로
 * 확인한다(AuthSignupMapperSqlTest와 같은 패턴, 2026-08-07).
 */
class AccountVerificationMapperSqlTest {

    @Test
    void incrementAttemptCountUpdatesRowByTransactionId() throws Exception {
        String sql = resource("mapper/account/AccountVerificationMapper.xml");

        String block = sql.substring(
                sql.indexOf("<update id=\"incrementAttemptCount\">"),
                sql.indexOf("</update>", sql.indexOf("<update id=\"incrementAttemptCount\">")));

        assertTrue(block.contains("attempt_count = attempt_count + 1"));
        assertTrue(block.contains("WHERE transaction_id = #{transactionId}"));
    }

    private String resource(String path) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
