package com.aewol.domain.account.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AccountServiceImplTest는 AccountMapper를 mock으로 대체하기 때문에 실제 SQL 문구는
 * 검증하지 못한다. updateStatus가 INACTIVE 전환 시 is_primary도 같이 내리는지는
 * XML 원문 문자열 검사로 확인한다(AuthSignupMapperSqlTest와 같은 패턴, 2026-08-07).
 */
class AccountMapperSqlTest {

    @Test
    void updateStatusClearsIsPrimaryOnlyWhenTransitioningToInactive() throws Exception {
        String accountSql = resource("mapper/account/AccountMapper.xml");

        String updateStatusBlock = accountSql.substring(
                accountSql.indexOf("<update id=\"updateStatus\">"),
                accountSql.indexOf("</update>", accountSql.indexOf("<update id=\"updateStatus\">")));

        assertTrue(updateStatusBlock.contains("CASE WHEN #{status} = 'INACTIVE' THEN 0 ELSE is_primary END"));
    }

    private String resource(String path) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
